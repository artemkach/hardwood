# Codebase Information

## Project Identity

- **Name**: Hardwood
- **Group ID**: `dev.hardwood`
- **Version**: 1.1.0-SNAPSHOT (latest release: 1.0.0.Final)
- **License**: Apache License 2.0
- **Repository**: https://github.com/hardwood-hq/hardwood
- **Website**: https://hardwood.dev/
- **Inception Year**: 2026

## Purpose

A minimal-dependency Apache Parquet file format parser in Java, available as both a library and a CLI tool. Implements the Parquet format specification from scratch (custom Thrift, all encodings, Dremel algorithm) without requiring Hadoop or parquet-java.

## Technology Stack

- **Language**: Java 21+ (runtime), Java 25+ (build, for multi-release JAR with FFM support)
- **Build System**: Apache Maven (wrapper: `./mvnw`)
- **Testing**: JUnit 5, AssertJ, Testcontainers (Docker required)
- **Native Image**: GraalVM/Mandrel via Quarkus (CLI module)
- **CLI Framework**: Picocli (via `quarkus-picocli`)
- **Documentation**: MkDocs (Python, Docker-based)
- **CI/CD**: GitHub Actions

## Module Layout

| Module | Artifact ID | Description |
|--------|-------------|-------------|
| `core` | `hardwood-core` | Core Parquet reader/writer library (zero required deps) |
| `s3` | `hardwood-s3` | S3 object store backend (custom HTTP client, no AWS SDK) |
| `aws-auth` | `hardwood-aws-auth` | Bridges AWS SDK credential chain to Hardwood's S3 types |
| `avro` | `hardwood-avro` | Avro GenericRecord materialization |
| `cli` | `hardwood-cli` | GraalVM native CLI (`hardwood` binary) with TUI (`dive`) |
| `parquet-java-compat` | `hardwood-parquet-java-compat` | Drop-in parquet-java API shims |
| `bom` | `hardwood-bom` | Bill of Materials for dependency management |
| `test-bom` | `hardwood-test-bom` | Test dependency BOM |
| `test-support` | `hardwood-test-support` | Shared test utilities |
| `error-prone-checks` | `hardwood-error-prone-checks` | Custom Error Prone rules (`NoVar`, `NoLegacyJavadoc`) |
| `integration-test` | N/A | Cross-implementation compatibility tests |
| `parquet-testing-runner` | N/A | Runner for apache/parquet-testing files |
| `performance-testing` | N/A | JMH benchmarks (enabled with `-Pperformance-test`) |

## Key External Dependencies (all optional for core)

- `snappy-java` — Snappy compression
- `zstd-jni` — ZSTD compression
- `lz4-java` (at.yawk) — LZ4 compression
- `brotli4j` — Brotli compression
- `software.amazon.awssdk:auth` — AWS credential resolution (aws-auth module only)
- `org.apache.avro:avro` — Avro support (avro module only)

## Developers

- Gunnar Morling
- Rion Williams
- Fawzi Essam
