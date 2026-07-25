/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.s3;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/// Transport feasibility checks for the performance-sandbox spike (item c):
/// verifies the pinned S3Proxy image's latency middleware and its defective
/// stream throttle, and smoke-tests Toxiproxy (latency, bandwidth,
/// two-connection overlap) as the bandwidth-impairment layer the sandbox
/// needs because the S3Proxy throttle cannot provide it.
///
/// These are *feasibility* checks with generous tolerances, not calibration —
/// per-profile calibration with predeclared residuals is a later deliverable.
/// Requests are plain anonymous HTTP GETs (`S3PROXY_AUTHORIZATION=none`):
/// the checks characterize the impairment layers themselves, so the real
/// `S3InputFile` stack is deliberately out of the loop here.
///
/// Container images are pinned: S3Proxy via [S3ProxyContainers#IMAGE]
/// (s3proxy 3.1.0 release commit `6597ca59`), Toxiproxy by digest below.
@Testcontainers
class TransportImpairmentSpikeTest {

    /// Toxiproxy v2.12.0, pinned by digest (selected by this spike; the tag
    /// alone is mutable). If this becomes durable CI infrastructure, mirror
    /// it via `s3proxy-mirror` like the S3Proxy image.
    static final String TOXIPROXY_IMAGE =
            "ghcr.io/shopify/toxiproxy@sha256:9378ed52a28bc50edc1350f936f518f31fa95f0d15917d6eb40b8e376d1a214e";

    private static final int TOXIPROXY_API_PORT = 8474;
    private static final int PROXY_PLAIN_PORT = 18090;
    private static final int PROXY_LATENCY_PORT = 18091;
    private static final int PROXY_BANDWIDTH_PORT = 18092;

    private static final String OBJECT_KEY = "blob.bin";
    private static final int OBJECT_SIZE = 2 * 1024 * 1024;

    /// Toxiproxy bandwidth toxic rate in KB/s (kilobytes, decimal, per TCP
    /// connection — the unit its own docs declare).
    private static final int BANDWIDTH_KBPS = 1024;

    private static final Network NETWORK = Network.newNetwork();

    /// Plain S3Proxy backend, no impairment: upstream for Toxiproxy and the
    /// timing baseline. Anonymous access keeps SigV4 out of the picture.
    @Container
    static final GenericContainer<?> S3PROXY = anonymousS3Proxy()
            .withNetwork(NETWORK)
            .withNetworkAliases("s3proxy");

    /// S3Proxy with its own latency middleware active: 300 ms on `get`.
    @Container
    static final GenericContainer<?> S3PROXY_LATENCY = anonymousS3Proxy()
            .withEnv("S3PROXY_JAVA_OPTS", "-Ds3proxy.latency-blobstore.get.latency=300");

    /// S3Proxy with its own stream throttle at a realistic rate (50 MiB/s =
    /// 52_428_800 bytes/s; the property unit is bytes per millisecond-ish —
    /// any plausible reading fails the same way, which is the point).
    @Container
    static final GenericContainer<?> S3PROXY_THROTTLE = anonymousS3Proxy()
            .withEnv("S3PROXY_JAVA_OPTS", "-Ds3proxy.latency-blobstore.get.speed=52429");

    @Container
    static final GenericContainer<?> TOXIPROXY = new GenericContainer<>(TOXIPROXY_IMAGE)
            .withNetwork(NETWORK)
            .withExposedPorts(TOXIPROXY_API_PORT, PROXY_PLAIN_PORT, PROXY_LATENCY_PORT, PROXY_BANDWIDTH_PORT);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static GenericContainer<?> anonymousS3Proxy() {
        byte[] bytes = new byte[OBJECT_SIZE];
        ThreadLocalRandom.current().nextBytes(bytes);
        return new GenericContainer<>(S3ProxyContainers.IMAGE)
                .withExposedPorts(S3ProxyContainers.PORT)
                .withEnv("S3PROXY_AUTHORIZATION", "none")
                .withEnv("S3PROXY_ENDPOINT", "http://0.0.0.0:" + S3ProxyContainers.PORT)
                .withEnv("JCLOUDS_PROVIDER", "filesystem")
                .withEnv("JCLOUDS_FILESYSTEM_BASEDIR", "/data")
                .withCopyToContainer(Transferable.of(bytes), S3ProxyContainers.objectPath(OBJECT_KEY));
    }

    @BeforeAll
    static void createProxies() throws Exception {
        // Toxiproxy reaches S3Proxy over the shared container network; the
        // test reaches each listener through its mapped host port.
        createProxy("plain", PROXY_PLAIN_PORT);
        createProxy("lat", PROXY_LATENCY_PORT);
        createProxy("bw", PROXY_BANDWIDTH_PORT);
        addToxic("lat", "{\"type\":\"latency\",\"stream\":\"downstream\","
                + "\"attributes\":{\"latency\":300,\"jitter\":0}}");
        addToxic("bw", "{\"type\":\"bandwidth\",\"stream\":\"downstream\","
                + "\"attributes\":{\"rate\":" + BANDWIDTH_KBPS + "}}");
    }

    @AfterAll
    static void closeNetwork() {
        NETWORK.close();
    }

    // ==================== S3Proxy middleware verification ====================

    @Test
    void latencyMiddlewareAddsConfiguredDelay() throws Exception {
        long baseline = medianGetNanos(s3proxyUri(S3PROXY), 3);
        long delayed = medianGetNanos(s3proxyUri(S3PROXY_LATENCY), 3);
        // 300 ms configured; require at least 200 ms of added delay so host
        // jitter cannot fake a pass or a fail.
        assertThat(delayed - baseline)
                .as("LatencyBlobStore should add ~300 ms to GET")
                .isGreaterThan(200_000_000L);
    }

    @Test
    void streamThrottleIsDefectiveAtRealisticRates() throws Exception {
        // ThrottledInputStream computes Thread.sleep(size/speed,
        // (size % speed) * 1_000_000): any non-zero remainder yields a
        // nanosecond argument far beyond the valid 0..999_999 range, so a
        // GET at a ~50 MiB/s setting fails outright rather than throttling.
        // This confirms the research doc's defect analysis on the pinned
        // image and is why Toxiproxy supplies the bandwidth dimension.
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(s3proxyUri(S3PROXY_THROTTLE)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("throttled GET fails with an HTTP error instead of throttling")
                .isEqualTo(400);
        assertThat(response.body()).contains("nanosecond timeout value out of range");
    }

    // ==================== Toxiproxy smoke checks ====================

    @Test
    void latencyToxicAddsConfiguredDelay() throws Exception {
        long plain = medianGetNanos(proxyUri(PROXY_PLAIN_PORT), 3);
        long delayed = medianGetNanos(proxyUri(PROXY_LATENCY_PORT), 3);
        assertThat(delayed - plain)
                .as("downstream latency toxic should add ~300 ms")
                .isGreaterThan(200_000_000L);
    }

    @Test
    void bandwidthToxicThrottlesToConfiguredRate() throws Exception {
        // 2 MiB at 1024 KB/s (decimal kilobytes) ≈ 2.05 s. Accept a broad
        // band: transfer must be visibly throttled (≥ 1.4 s) and not
        // pathologically slow (≤ 4 s).
        long nanos = timedGetNanos(proxyUri(PROXY_BANDWIDTH_PORT));
        assertThat(nanos)
                .as("2 MiB through a 1024 KB/s bandwidth toxic")
                .isBetween(1_400_000_000L, 4_000_000_000L);
    }

    @Test
    void bandwidthToxicIsPerConnection() throws Exception {
        // Two concurrent GETs on separate connections must EACH get the full
        // per-connection rate (Toxiproxy's bandwidth toxic is per TCP
        // connection, not aggregate): each concurrent transfer completes in
        // about the solo duration, and the pair overlaps rather than
        // serializing. A serialized pair would take ~2x solo.
        long solo = timedGetNanos(proxyUri(PROXY_BANDWIDTH_PORT));

        HttpClient c1 = HttpClient.newHttpClient();
        HttpClient c2 = HttpClient.newHttpClient();
        long start = System.nanoTime();
        CompletableFuture<Long> f1 = timedGetAsync(c1, proxyUri(PROXY_BANDWIDTH_PORT));
        CompletableFuture<Long> f2 = timedGetAsync(c2, proxyUri(PROXY_BANDWIDTH_PORT));
        long d1 = f1.join();
        long d2 = f2.join();
        long wallClock = System.nanoTime() - start;

        assertThat(d1).as("connection 1 gets the full per-connection rate")
                .isLessThan(solo * 3 / 2);
        assertThat(d2).as("connection 2 gets the full per-connection rate")
                .isLessThan(solo * 3 / 2);
        assertThat(wallClock).as("the two transfers overlap instead of serializing")
                .isLessThan(solo * 7 / 4);
    }

    // ==================== helpers ====================

    private static URI s3proxyUri(GenericContainer<?> container) {
        return URI.create(S3ProxyContainers.endpoint(container)
                + "/" + S3ProxyContainers.BUCKET + "/" + OBJECT_KEY);
    }

    private static URI proxyUri(int listenPort) {
        return URI.create("http://" + TOXIPROXY.getHost() + ":"
                + TOXIPROXY.getMappedPort(listenPort)
                + "/" + S3ProxyContainers.BUCKET + "/" + OBJECT_KEY);
    }

    private static long medianGetNanos(URI uri, int samples) throws Exception {
        long[] times = new long[samples];
        for (int i = 0; i < samples; i++) {
            times[i] = timedGetNanos(uri);
        }
        Arrays.sort(times);
        return times[samples / 2];
    }

    private static long timedGetNanos(URI uri) throws Exception {
        long start = System.nanoTime();
        HttpResponse<byte[]> response = HTTP.send(
                HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        long elapsed = System.nanoTime() - start;
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).hasSize(OBJECT_SIZE);
        return elapsed;
    }

    private static CompletableFuture<Long> timedGetAsync(HttpClient client, URI uri) {
        long start = System.nanoTime();
        return client.sendAsync(
                        HttpRequest.newBuilder(uri).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.body()).hasSize(OBJECT_SIZE);
                    return System.nanoTime() - start;
                });
    }

    private static void createProxy(String name, int listenPort) throws IOException, InterruptedException {
        toxiproxyApi("/proxies", "{\"name\":\"" + name + "\",\"listen\":\"0.0.0.0:" + listenPort
                + "\",\"upstream\":\"s3proxy:" + S3ProxyContainers.PORT + "\"}");
    }

    private static void addToxic(String proxyName, String toxicJson) throws IOException, InterruptedException {
        toxiproxyApi("/proxies/" + proxyName + "/toxics", toxicJson);
    }

    private static void toxiproxyApi(String path, String body) throws IOException, InterruptedException {
        URI uri = URI.create("http://" + TOXIPROXY.getHost() + ":"
                + TOXIPROXY.getMappedPort(TOXIPROXY_API_PORT) + path);
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(uri)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IllegalStateException("Toxiproxy API " + path + " failed: "
                    + response.statusCode() + " " + response.body());
        }
    }
}
