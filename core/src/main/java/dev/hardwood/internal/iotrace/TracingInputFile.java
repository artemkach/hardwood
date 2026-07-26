/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.iotrace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.hardwood.InputFile;

/// An [InputFile] wrapper that records every `readRange` call as an immutable
/// `(offset, length)` record, generalizing
/// [dev.hardwood.internal.reader.CountingInputFile] from counts to full
/// logical-read records.
///
/// This is the independent execution record at the `InputFile` seam that
/// plan-vs-execution conformance matches against captured node IDs: the
/// capture's own `RequestEvent` attempts are emitted by the instrumented code,
/// so a wiring bug there could go unnoticed without a second, dumber witness
/// that simply sees what crossed the seam.
///
/// Thread-safe: reads arrive from decode virtual threads and common-pool
/// prefetch tasks concurrently.
public final class TracingInputFile implements InputFile {

    /// One observed `readRange` invocation.
    public record TracedRead(long offset, int length) {}

    private final InputFile delegate;
    private final List<TracedRead> reads = new CopyOnWriteArrayList<>();

    public TracingInputFile(InputFile delegate) {
        this.delegate = delegate;
    }

    /// All reads observed so far, in arrival order.
    public List<TracedRead> reads() {
        return List.copyOf(reads);
    }

    @Override
    public void open() throws IOException {
        delegate.open();
    }

    @Override
    public ByteBuffer readRange(long offset, int length) throws IOException {
        reads.add(new TracedRead(offset, length));
        return delegate.readRange(offset, length);
    }

    @Override
    public long length() throws IOException {
        return delegate.length();
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
