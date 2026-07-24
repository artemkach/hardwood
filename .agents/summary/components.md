# Components

## Published Library Modules

### hardwood-core

The primary library. Implements the complete Parquet read path and an early-stage write path.

**Key packages:**
- `dev.hardwood` — Top-level types: `InputFile`, `OutputFile`, `Hardwood` facade, `HardwoodContext`, `Validity`
- `dev.hardwood.reader` — Public reader API: `ParquetFileReader`, `RowReader`, `ColumnReader`, `ColumnReaders`, `FilterPredicate`, `ReaderConfig`
- `dev.hardwood.writer` — Public writer API: `ParquetFileWriter`, `WriterConfig`, `ColumnBatch`
- `dev.hardwood.schema` — Schema types: `FileSchema`, `ColumnSchema`, `SchemaNode`, `ColumnProjection`
- `dev.hardwood.metadata` — Metadata records: `FileMetaData`, `RowGroup`, `ColumnChunk`, `ColumnMetaData`, `Statistics`, `LogicalType`, `FieldPath`
- `dev.hardwood.row` — Row value types: `PqStruct`, `PqList`, `PqMap`, `PqIntList`, `PqLongList`, `PqDoubleList`, `PqVariant`, `PqInterval`, `FieldAccessor`
- `dev.hardwood.jfr` — JFR event definitions for observability
- `dev.hardwood.internal.*` — All implementation details (not for external use, but accessible within the repo)

**Internal subsystems (under `dev.hardwood.internal`):**
- `thrift/` — Custom Thrift Compact Protocol reader/writer (~30 classes)
- `reader/` — Core reading machinery (~55 classes): page decoding, batch exchange, row assembly, fetch plans
- `encoding/` — All Parquet encoding decoders + RLE encoder, SIMD operations
- `compression/` — Decompressor registry and implementations (GZIP, Snappy, ZSTD, LZ4, Brotli, libdeflate)
- `predicate/` — Filter evaluation: row group, page, batch, and exact filtering (~30 classes)
- `writer/` — Writer internals: record shredding, column chunk buffering
- `variant/` — Parquet VARIANT logical type decoder (shredded + non-shredded)
- `bloomfilter/` — Split block bloom filter + XxHash64
- `conversion/` — Logical type value conversions
- `metadata/` — Internal page header types
- `schema/` — Projected schema calculation

### hardwood-s3

Zero-dependency S3 backend. Implements HTTP range requests and AWS SigV4 signing without the AWS SDK.

**Key types:**
- `S3InputFile` — `InputFile` implementation with suffix-range GET and 64 KB tail caching
- `S3Source` — Builder for constructing S3-backed input files
- `S3Credentials`, `S3CredentialsProvider` — Credential types
- `internal/S3Api` — Raw HTTP operations against S3
- `internal/Aws4Signer` — AWS Signature Version 4 implementation

### hardwood-aws-auth

Bridge between the AWS SDK credential chain and Hardwood's `S3CredentialsProvider`.

### hardwood-avro

Materializes Parquet rows as Avro `GenericRecord` instances.

**Key types:**
- `AvroRowReader` — Wraps `RowReader`, produces `GenericRecord` per row
- `AvroReaders` — Factory with overloads for filter pushdown and column projection
- `internal/AvroSchemaConverter` — Converts `FileSchema` to Avro `Schema`

### hardwood-parquet-java-compat

Drop-in API compatibility layer for code written against parquet-java. Provides shims for `ParquetReader<Group>`, `GroupReadSupport`, `HadoopInputFile`, `FilterApi`, schema types, and `Group`/`SimpleGroup`.

## CLI Module

### hardwood-cli

GraalVM native binary providing Parquet file inspection and conversion commands.

**Commands:**
- `hardwood info` — File overview (row count, row groups, columns, compression)
- `hardwood schema` — Schema display (flat or tree format)
- `hardwood print` — Print rows as table or JSON
- `hardwood convert` — Convert between formats (e.g., Parquet → CSV/JSON)
- `hardwood inspect columns` — Column chunk details
- `hardwood inspect row-groups` — Row group metadata
- `hardwood inspect pages` — Page-level breakdown
- `hardwood inspect dictionary` — Dictionary contents
- `hardwood footer` — Raw footer dump
- `hardwood dive` — Interactive TUI explorer

**Dive TUI (`cli/dive/`):**
An interactive terminal UI for deep Parquet file exploration. Screens include Overview, Schema (tree), Row Groups, Column Chunks, Pages, Column Index, Offset Index, Dictionary, Data Preview, and Footer. Uses viewport virtualization for performance on large files.

## Test & Build Modules

### hardwood-error-prone-checks

Custom Error Prone compiler checks enforced during build:
- `NoVar` — Forbids `var` syntax
- `NoLegacyJavadoc` — Enforces `///` Markdown JavaDoc (JEP 467)

### hardwood-test-support

Shared test utilities (Testcontainers helpers, fixture paths).

### integration-test / parquet-testing-runner

Cross-implementation compatibility testing against the apache/parquet-testing corpus.

### performance-testing

JMH micro-benchmarks and end-to-end performance tests (enabled with `-Pperformance-test`).
