# Documentation Index

> **For AI Assistants**: This file is the primary entry point for understanding the Hardwood codebase.
> Read this file first to determine which detailed document to consult for a given question.
> Each document below has a summary — use it to decide relevance before loading the full file.

## Project Summary

Hardwood is a from-scratch Apache Parquet reader/writer in Java with zero required dependencies (compression libraries are optional). It provides a Java library (`hardwood-core`) and a GraalVM native CLI tool (`hardwood-cli` with `hardwood dive` TUI). The project targets Java 21+ at runtime, requires Java 25+ to build, and uses Maven.

## Document Map

### [codebase_info.md](codebase_info.md)
**When to consult**: Project identity, version, license, module listing, tech stack overview.

Covers: group ID, artifact IDs, all 12 modules with their purposes, key dependencies, developer list. Start here for "what modules exist" or "what's the Maven coordinate" questions.

### [architecture.md](architecture.md)
**When to consult**: Understanding the read/write data flow, design decisions, system layers, predicate pushdown architecture.

Covers: layered architecture diagram (I/O → metadata → page decoding → batch exchange → row assembly), flat vs nested split, custom Thrift parser rationale, virtual thread parallelism, multi-release JAR strategy, InputFile abstraction, four-layer filter evaluation.

### [components.md](components.md)
**When to consult**: Detailed breakdown of what each module contains, package organization within modules, key classes per package.

Covers: `core` internal subsystems (thrift, reader, encoding, compression, predicate, writer, variant, bloomfilter), `s3` module types, `avro` module, `cli` commands and dive TUI screens, error-prone-checks, test modules.

### [interfaces.md](interfaces.md)
**When to consult**: Public API shapes, method signatures, usage patterns, how to construct and use readers/writers/filters.

Covers: `ParquetFileReader` API, `RowReader` accessor methods, `ColumnReader` batch API, `InputFile` factory methods, `FilterPredicate` sealed hierarchy, `ColumnProjection`, `ReaderConfig`, `WriterConfig`, `FileSchema.Builder`, row value types (`PqStruct`, `PqList`, `PqMap`, etc.), S3 builder API, Avro API, CLI commands.

### [data_models.md](data_models.md)
**When to consult**: Understanding Parquet format structure, metadata record fields, schema representation, type systems.

Covers: Parquet file structure (row groups → column chunks → pages), all metadata record types with fields (`FileMetaData`, `RowGroup`, `ColumnChunk`, `ColumnMetaData`, `Statistics`, `ColumnIndex`, `OffsetIndex`), schema types (`FileSchema`, `SchemaNode`, `ColumnSchema`, `FieldPath`), physical types, logical types, compression codecs, encodings, writer data model.

### [workflows.md](workflows.md)
**When to consult**: Understanding sequences of operations — how reading/writing/filtering actually executes, build/CI pipeline, test data generation, native build process.

Covers: Row-oriented read sequence, column-oriented read sequence, write path (shredding → encoding → flushing), predicate pushdown evaluation order, CI pipeline structure (PR build splits), release process, test fixture generation (PyArrow scripts), native CLI build flow (codec handling), documentation site workflow.

### [dependencies.md](dependencies.md)
**When to consult**: What external libraries are used, dependency management strategy, build tools.

Covers: Runtime deps per module (core has zero required), test deps, build-time tools (Error Prone, formatters, japicmp), BOM strategy, optional compression libraries, AWS SDK exclusion strategy.

## Quick Reference

| Question Type | Primary Document | Secondary |
|---------------|-----------------|-----------|
| "What module handles X?" | components.md | architecture.md |
| "How do I use the API?" | interfaces.md | — |
| "How does reading work internally?" | architecture.md | workflows.md |
| "What's in the metadata?" | data_models.md | — |
| "How is the build structured?" | workflows.md | dependencies.md |
| "What dependencies are needed?" | dependencies.md | codebase_info.md |
| "What's the project version/license?" | codebase_info.md | — |
| "How do filters work?" | architecture.md | interfaces.md |
| "How do I add a new encoding?" | architecture.md | components.md |
| "What CLI commands exist?" | interfaces.md | components.md |

## Key Files in the Repository

| File | Purpose |
|------|---------|
| `CLAUDE.md` | Coding conventions and rules for AI agents |
| `ARCHITECTURE.md` | Official architecture doc (ASCII-art data flow) |
| `ROADMAP.md` | Implementation status with checkbox tracking |
| `CONTRIBUTING.md` | Contribution workflow |
| `_designs/*.md` | Design documents for features (intended end state) |
| `FORMAT_COVERAGE.md` | parquet.thrift field coverage matrix |
| `PERFORMANCE.md` | Benchmark results and instructions |
| `NATIVE_BUILD.md` | GraalVM native image build details |
| `RELEASING.md` | Release process |
| `TESTING.md` | Manual testing recipes |
