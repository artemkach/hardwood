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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.hardwood.InputFile;

/// An [InputFile] wrapper that records every `readRange` call as an immutable
/// `(offset, length, begin, duration)` record, generalizing
/// [dev.hardwood.internal.reader.CountingInputFile] from counts to full
/// logical-read records.
///
/// This is the independent execution record at the `InputFile` seam that
/// plan-vs-execution conformance matches against captured node IDs: the
/// capture's own `RequestEvent` attempts are emitted by the instrumented code,
/// so a wiring bug there could go unnoticed without a second, dumber witness
/// that simply sees what crossed the seam.
///
/// Timing uses `System.nanoTime()` — monotonic elapsed time, valid for
/// durations and intra-run ordering, not convertible to time-of-day and not
/// comparable across processes. Conformance matching ignores it (structural
/// fields only); it exists for display and diagnostics, where the
/// local-vs-remote difference lives.
///
/// Thread-safe: reads arrive from decode virtual threads and common-pool
/// prefetch tasks concurrently.
public final class TracingInputFile implements InputFile {

    /// One observed `readRange` invocation. `beginNanos` and `durationNanos`
    /// are `System.nanoTime()`-based; a failed read is still recorded, with
    /// the duration up to the throw.
    public record TracedRead(long offset, int length, long beginNanos, long durationNanos) {

        /// Whether this read covers exactly `[offset, offset+length)` —
        /// the structural identity conformance matches on, ignoring timing.
        public boolean covers(long offset, int length) {
            return this.offset == offset && this.length == length;
        }
    }

    private final InputFile delegate;
    private final List<TracedRead> reads = new CopyOnWriteArrayList<>();

    public TracingInputFile(InputFile delegate) {
        this.delegate = delegate;
    }

    /// All reads observed so far, ordered by begin time. (Records are
    /// appended on completion, so raw list order is completion order;
    /// sorting by `beginNanos` restores invocation order under concurrency.)
    public List<TracedRead> reads() {
        List<TracedRead> sorted = new ArrayList<>(reads);
        sorted.sort(Comparator.comparingLong(TracedRead::beginNanos));
        return List.copyOf(sorted);
    }

    @Override
    public void open() throws IOException {
        delegate.open();
    }

    @Override
    public ByteBuffer readRange(long offset, int length) throws IOException {
        long begin = System.nanoTime();
        try {
            ByteBuffer result = delegate.readRange(offset, length);
            reads.add(new TracedRead(offset, length, begin, System.nanoTime() - begin));
            return result;
        }
        catch (IOException | RuntimeException e) {
            reads.add(new TracedRead(offset, length, begin, System.nanoTime() - begin));
            throw e;
        }
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
