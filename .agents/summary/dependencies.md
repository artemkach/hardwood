# Dependencies

## Runtime Dependencies

### hardwood-core

The core module has **zero required runtime dependencies**. All compression libraries are optional:

| Dependency | Group ID | Artifact ID | Purpose |
|------------|----------|-------------|---------|
| Snappy | `org.xerial.snappy` | `snappy-java` | Snappy decompression |
| ZSTD | `com.github.luben` | `zstd-jni` | Zstandard decompression |
| LZ4 | `at.yawk.lz4` | `lz4-java` | LZ4 decompression (Hadoop + raw) |
| Brotli | `com.aayushatharva.brotli4j` | `brotli4j` | Brotli decompression |

GZIP decompression uses `java.util.zip` (JDK built-in), with an optional FFM-based libdeflate fast path on Java 22+.

### hardwood-s3

Zero external dependencies. Implements HTTP client and AWS SigV4 signing from scratch using `java.net.http.HttpClient`.

### hardwood-aws-auth

| Dependency | Purpose |
|------------|---------|
| `hardwood-s3` | Credential types |
| `software.amazon.awssdk:auth` (v2.42.17) | AWS credential resolution chain |

Excludes most transitive AWS SDK modules (signing, HTTP auth, checksums) — only credential resolution is needed.

### hardwood-avro

| Dependency | Purpose |
|------------|---------|
| `hardwood-core` | Parquet reading |
| `org.apache.avro:avro` | Avro schema and GenericRecord |

### hardwood-cli

Built on Quarkus for native image support:
| Dependency | Purpose |
|------------|---------|
| `quarkus-picocli` | CLI framework |
| `hardwood-core` + all codec libs | Full reading support |
| `hardwood-s3` | S3 support in CLI |
| `io.netty:netty-buffer` | Required by brotli4j in native image |

### hardwood-parquet-java-compat

| Dependency | Purpose |
|------------|---------|
| `hardwood-core` | Underlying reader |
| `hardwood-s3` | S3 support via HadoopInputFile shim |

No actual Hadoop or parquet-java dependency — all API types are shimmed.

## Test Dependencies

| Dependency | Purpose |
|------------|---------|
| JUnit 5 (`junit-jupiter`) | Test framework |
| AssertJ (`assertj-core`) | Fluent assertions |
| Testcontainers | S3 integration tests (LocalStack/s3proxy) |
| Log4j 2 | Test logging |
| DuckDB JDBC | Cross-validation oracle |
| `parquet-column` / `parquet-format-structures` | Reference impl oracle for bloom filter tests |
| JTS (`jts-core`) | Geospatial type testing |

## Build-Time Dependencies

| Tool | Purpose |
|------|---------|
| Error Prone (v2.49.0) | Static analysis during compilation |
| `hardwood-error-prone-checks` | Custom `NoVar` and `NoLegacyJavadoc` rules |
| `license-maven-plugin` (Mycila) | License header enforcement |
| `formatter-maven-plugin` | Eclipse-based code formatting |
| `impsort-maven-plugin` | Import ordering |
| `japicmp-maven-plugin` | API compatibility checking |
| `flatten-maven-plugin` | POM flattening for publication |
| `native-maven-plugin` (GraalVM) | Native image compilation |
| `jreleaser-maven-plugin` | Release to Maven Central + GitHub |

## Dependency Management Strategy

- BOM (`hardwood-bom`) centralizes versions for published modules
- Test BOM (`hardwood-test-bom`) centralizes test dependency versions
- All plugin versions declared in parent `pom.xml` `<pluginManagement>` — module POMs reference by groupId/artifactId only
- Compression libs are `<optional>true</optional>` — users include only what they need
- AWS SDK dependency in `aws-auth` aggressively excludes unused transitive modules
