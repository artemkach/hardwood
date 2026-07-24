# AGENTS.md

> Hardwood — a minimal-dependency Apache Parquet reader/writer in Java.
> Repository: https://github.com/hardwood-hq/hardwood | Docs: https://hardwood.dev/

## Directory Map

```
hardwood/
├── core/              → hardwood-core: Parquet reader (+ early writer), zero required deps
├── s3/               → hardwood-s3: S3 backend (custom HTTP + SigV4, no AWS SDK)
├── aws-auth/         → hardwood-aws-auth: AWS SDK credential bridge
├── avro/             → hardwood-avro: Avro GenericRecord materialization
├── cli/              → hardwood-cli: GraalVM native binary + dive TUI
│   └── src/.../dive/ → Interactive terminal UI (Quarkus + picocli)
├── parquet-java-compat/ → Drop-in parquet-java API shims (no Hadoop dep)
├── bom/              → Published BOM for dependency management
├── test-bom/         → Test dependency BOM
├── test-support/     → Shared test utilities
├── error-prone-checks/ → Custom compiler checks: NoVar, NoLegacyJavadoc
├── integration-test/ → Cross-impl compat tests
├── parquet-testing-runner/ → Runner for apache/parquet-testing corpus
├── performance-testing/   → JMH benchmarks (enable: -Pperformance-test)
├── _designs/         → Design documents (56 docs, intended end state)
├── docs/             → MkDocs documentation site (Diátaxis model)
├── tools/            → Scripts: api-report.sh, simple-datagen.py, contributors.py
└── .agents/summary/  → Generated codebase documentation for AI agents
```

## Core Package Layout

```
dev.hardwood/
├── reader/           → PUBLIC: ParquetFileReader, RowReader, ColumnReader, FilterPredicate
├── writer/           → PUBLIC: ParquetFileWriter, WriterConfig, ColumnBatch
├── schema/           → PUBLIC: FileSchema, ColumnSchema, ColumnProjection
├── metadata/         → PUBLIC: FileMetaData, RowGroup, LogicalType, FieldPath
├── row/              → PUBLIC: PqStruct, PqList, PqMap, PqVariant, PqInterval
├── jfr/              → PUBLIC: JFR events for observability
└── internal/         → PRIVATE (but accessible within this repo's modules)
    ├── thrift/       → Custom Thrift Compact Protocol (~30 readers/writers)
    ├── reader/       → Page decoding, batch exchange, row assembly (~55 classes)
    ├── encoding/     → All Parquet encodings + SIMD ops
    ├── compression/  → GZIP, Snappy, ZSTD, LZ4, Brotli, libdeflate
    ├── predicate/    → 4-layer filter: row group → page → batch → exact
    ├── writer/       → Record shredding, column chunk buffering
    ├── variant/      → VARIANT logical type (shredded + non-shredded)
    ├── bloomfilter/  → Split block bloom filter + XxHash64
    └── conversion/   → Logical type value conversions
```

## Key Entry Points

| Task | Start Here |
|------|-----------|
| Understanding the read path | `core/.../reader/ParquetFileReader.java` |
| Page decoding | `core/.../internal/reader/PageDecoder.java` |
| Dremel record assembly | `core/.../internal/reader/NestedLevelComputer.java` |
| Predicate pushdown | `core/.../internal/predicate/RowGroupFilterEvaluator.java` |
| Writer | `core/.../writer/ParquetFileWriter.java` |
| Record shredding | `core/.../internal/writer/RecordShredder.java` |
| S3 reads | `s3/.../s3/S3InputFile.java` |
| CLI commands | `cli/.../cli/command/` |
| Dive TUI | `cli/.../cli/dive/DiveApp.java` |
| Avro integration | `avro/.../avro/AvroRowReader.java` |

## Repo-Specific Conventions

These deviate from Java/Maven defaults and are enforced:

- **No `var`** — enforced by `NoVar` Error Prone check; always use explicit types.
- **`///` Markdown JavaDoc** — enforced by `NoLegacyJavadoc`; no `/** */` block comments. Use backtick-fenced code blocks, `[ClassName]` links, inline backticks instead of `{@code}`.
- **Flat vs Nested split** — schemas without nesting route through `FlatRowReader`/`FlatColumnWorker` (skips rep/def levels). Touch both paths when modifying the read pipeline.
- **Virtual threads** — column batch fetching uses `Executors.newVirtualThreadPerTaskExecutor()`. No fixed thread pools.
- **Multi-release JAR** — `core` has `src/main/java22/` for Java 22+ FFM (libdeflate) and Vector API. The build requires Java 25+ but the output JAR runs on Java 21+.
- **Internal packages are repo-accessible** — `dev.hardwood.internal.*` is off-limits for external users but modules within this repo (cli, avro, compat) import directly. Don't promote internal APIs to public just to avoid the import.
- **Plugin versions in parent only** — all `<version>` tags live in the parent POM's `<pluginManagement>`. Module POMs never declare plugin versions.
- **GitHub Actions by SHA** — actions are always pinned to commit SHAs, never tags.
- **Design docs before implementation** — large features require a `_designs/*.md` document describing the intended end state before coding begins.
- **Commit format** — `#<issue> <description>` (e.g., `#90 Include file name in exceptions`). Body explains *why*, not *what*.

## Config & CI Details

| File | What it controls |
|------|-----------------|
| `.java-version` | Java 25 (for SDKMAN/asdf) |
| `.mvn/jvm.config` | JVM args for Maven process |
| `etc/eclipse-formatter-config.xml` | Code formatting rules |
| `.github/workflows/pr-build.yml` | PR CI: parallel jobs for core, s3, cli, compat, aws-auth |
| `.github/workflows/main-build.yml` | Main branch: full verify |
| `.github/workflows/release.yml` | Release pipeline (JReleaser → Maven Central) |
| `.github/workflows/performance.yml` | Performance regression tracking |
| `pom.xml` (parent) | All plugin versions, profiles (`qa`, `quick`, `performance-test`, `deployment`, `publication`, `release`) |

## Tools & Scripts

| Script | Purpose |
|--------|---------|
| `tools/api-report.sh` | Generate japicmp API change report across published modules |
| `tools/simple-datagen.py` | Generate test .parquet fixtures with PyArrow |
| `tools/contributors.py` | Generate contributor list for releases |
| `tools/parquet_annotators.py` | Annotate parquet files with metadata |
| `tools/strip-svg-metadata.sh` | Clean SVG files |
| `release.sh` | Release automation script |
| `cli/build-cli-docker.sh` | Build native CLI Docker image |

## Test Data Generation

Test Parquet files live in `core/src/test/resources/`. Generate/regenerate with:
```bash
source .docker-venv/bin/activate
python tools/simple-datagen.py
```
Uses Python 3.10–3.14 with PyArrow 24.0.0 (pinned in `requirements.txt`). Upgrading PyArrow requires regenerating all affected fixtures.

## Detailed Documentation

For deeper analysis, see `.agents/summary/index.md` which indexes:
- `architecture.md` — System layers and design decisions
- `components.md` — Per-module package breakdown
- `interfaces.md` — Full public API reference
- `data_models.md` — Parquet format types and metadata records
- `workflows.md` — Read/write/filter execution sequences
- `dependencies.md` — All external and build-time deps

## Custom Instructions

<!-- This section is maintained by developers and agents during day-to-day work.
     It is NOT auto-generated by codebase-summary and MUST be preserved during refreshes.
     Add project-specific conventions, gotchas, and workflow requirements here. -->
