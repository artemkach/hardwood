/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.iotrace;

/// Thread-confined install handshake for wiring a [IoTraceContext] into a
/// reader without adding a supported public API.
///
/// The performance-testing module installs a context, synchronously builds a
/// reader (whose [dev.hardwood.internal.reader.RowGroupIterator]s consume the
/// context *at construction*, on the build thread), then reads. The install
/// `ThreadLocal` is only a transient channel for the synchronous build — the
/// capture state itself lives on the [IoTraceContext] and is carried onto the
/// iterator and request objects **by reference**, so it survives the
/// virtual-thread / common-pool hand-offs that a `ThreadLocal` would not.
///
/// This is why the spike's "no process-global capture state, no caller-thread
/// affinity" criterion is met: the only thread-local datum is a pending
/// install pointer, present for the duration of one synchronous build and
/// never consulted during asynchronous plan construction or request
/// execution. Concurrent readers each install their own context; they never
/// share the pending slot because installation and consumption are confined
/// to the installing thread.
public final class IoTraceControl {

    private static final ThreadLocal<IoTraceContext> PENDING = new ThreadLocal<>();

    private IoTraceControl() {
    }

    /// Installs a context for the current thread's next synchronous reader
    /// build. The returned scope clears the install slot; capture continues
    /// via the context reference the built reader now holds.
    public static Scope install(IoTraceContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        IoTraceContext previous = PENDING.get();
        PENDING.set(context);
        return new Scope(previous);
    }

    /// Returns the pending context for the current thread, or `null` if none
    /// is installed. Consumed at iterator construction; the disabled path sees
    /// `null` and does no capture work.
    public static IoTraceContext pending() {
        return PENDING.get();
    }

    public static final class Scope implements AutoCloseable {

        private final IoTraceContext previous;

        private Scope(IoTraceContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                PENDING.remove();
            }
            else {
                PENDING.set(previous);
            }
        }
    }
}
