# Workflows

## Read Path (Row-Oriented)

```mermaid
sequenceDiagram
    participant User
    participant PFR as ParquetFileReader
    participant PMR as ParquetMetadataReader
    participant RGI as RowGroupIterator
    participant CW as ColumnWorker (virtual thread)
    participant PD as PageDecoder
    participant RR as RowReader

    User->>PFR: open(InputFile)
    PFR->>PMR: readFooter()
    PMR-->>PFR: FileMetaData + FileSchema

    User->>PFR: rowReader(filter?)
    PFR->>RGI: create(rowGroups, filter)
    RGI->>RGI: evaluateRowGroupFilter (statistics + bloom)
    RGI->>CW: spawn per-column workers
    CW->>PD: decodePage()
    PD-->>CW: batch of values
    CW-->>RR: BatchExchange (producer→consumer)
    RR-->>User: hasNext() / getXxx()
```

## Read Path (Column-Oriented)

```mermaid
sequenceDiagram
    participant User
    participant PFR as ParquetFileReader
    participant CR as ColumnReader
    participant SE as SelectionEngine
    participant CW as ColumnWorker

    User->>PFR: buildColumnReader(fieldPath)
    PFR->>CR: create
    User->>CR: filter(predicate)?
    CR->>SE: compile selection engine

    loop per batch
        User->>CR: nextBatch()
        CR->>CW: fetchBatch
        CW-->>CR: decoded values + validity
        CR->>SE: apply selection (if filtered)
        CR-->>User: batch size + values
    end
```

## Write Path

```mermaid
sequenceDiagram
    participant User
    participant PFW as ParquetFileWriter
    participant RS as RecordShredder
    participant CCB as ColumnChunkBuffer
    participant OUT as OutputFile

    User->>PFW: open(OutputFile, schema, config)
    User->>PFW: writeColumnBatch(batch)
    PFW->>RS: shred records into columns
    RS-->>CCB: definition/repetition levels + values
    CCB->>CCB: encode page (PLAIN/RLE_DICTIONARY)

    Note over PFW: Row group size threshold reached
    PFW->>OUT: flush row group (page bytes)

    User->>PFW: close()
    PFW->>OUT: write footer (Thrift-encoded FileMetaData)
    PFW->>OUT: write PAR1 magic
```

## Predicate Pushdown Flow

```mermaid
flowchart TD
    START[User provides FilterPredicate] --> RG_EVAL
    RG_EVAL[RowGroupFilterEvaluator<br/>statistics + bloom filter] -->|skip| RG_SKIP[Skip entire row group]
    RG_EVAL -->|may match| PAGE_EVAL

    PAGE_EVAL[PageFilterEvaluator<br/>column index min/max] -->|skip| PAGE_SKIP[Skip page]
    PAGE_EVAL -->|may match| DECODE

    DECODE[Decode page values] --> BATCH_FILTER
    BATCH_FILTER[BatchFilterCompiler<br/>drain-side per-batch] -->|no match| BATCH_SKIP[Skip batch]
    BATCH_FILTER -->|matches| EXACT

    EXACT[SelectionEngine<br/>exact row filtering] --> USER[Return matched rows]
```

## Build & CI Workflow

```mermaid
flowchart LR
    subgraph "PR Build (GitHub Actions)"
        A[build-core] --> B[build-s3]
        A --> C[build-cli]
        A --> D[build-compat]
        B --> E[build-aws-auth]
    end

    subgraph "Main Build"
        F[Full verify] --> G[Upload artifacts]
    end

    subgraph "Release"
        H[mvn release:prepare] --> I[deploy to staging]
        I --> J[JReleaser → Maven Central + GitHub Release]
    end
```

## Test Data Generation

```mermaid
flowchart LR
    PY[Python scripts<br/>tools/simple-datagen.py] -->|PyArrow| PARQUET[.parquet fixtures]
    PARQUET --> TEST[Unit tests<br/>core/src/test/resources/]
    PT[apache/parquet-testing<br/>submodule] --> RUNNER[parquet-testing-runner]
```

## Native CLI Build

```mermaid
flowchart TD
    MVN[./mvnw -Dnative package -pl cli -am] --> QUARKUS[Quarkus Native Image Build]
    QUARKUS --> GRAAL[GraalVM native-image]
    GRAAL --> BINARY[cli/target/hardwood-cli]

    subgraph "Codec Handling"
        LIBS[Snappy/ZSTD/LZ4 .so/.dylib] -->|unpacked at build| LIB_DIR[lib/ directory]
        LIB_DIR -->|System.load at startup| BINARY
        BROTLI[brotli4j] -->|resource-config.json| BINARY
    end
```

## Documentation Site

Built with MkDocs (Python), Dockerized:
1. `docker build -t hardwood-docs docs/` — Build image
2. `docker run --rm -p 8000:8000 -v "$(pwd):/repo" hardwood-docs` — Live preview at localhost:8000
3. GitHub Actions publishes to hardwood.dev on merge to main

Content follows the Diátaxis model: `tutorial/`, `how-to/`, `reference/`, `concepts/`.
