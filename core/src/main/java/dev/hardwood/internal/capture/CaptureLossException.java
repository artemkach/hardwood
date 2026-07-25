/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.capture;

/// Thrown by [PlanExtractor] when a recording fails the dual-closure-plus-
/// manifest protocol: data loss, an absent or duplicated seal, an unknown
/// execution ID, a count or hash mismatch, a dangling edge, a duplicate node
/// ID, an unplanned request, or a semantic conformance violation.
///
/// Rejection is the point: a lossy or defective capture must be refused rather
/// than silently scored, because a plan being scored that is not the plan that
/// ran makes every downstream number fiction.
public final class CaptureLossException extends RuntimeException {

    public CaptureLossException(String message) {
        super(message);
    }
}
