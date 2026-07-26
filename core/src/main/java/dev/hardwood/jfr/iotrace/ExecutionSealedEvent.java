/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.jfr.iotrace;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// The execution seal, emitted after the supported execution scope is
/// quiescent (reader close, for the one-row-group v0). Closes the request
/// stream with an order-independent attempt hash so a lost duplicate, retry,
/// or unplanned request is detectable — which the plan seal alone cannot do.
@Name("dev.hardwood.iotrace.ExecutionSealed")
@Label("Execution Sealed")
@Category({"Hardwood", "IO Trace"})
@Description("Execution seal closing the request stream with an order-independent hash")
@Enabled(false)
@StackTrace(false)
public class ExecutionSealedEvent extends Event {

    @Label("Execution ID")
    public long executionId;

    @Label("Request Attempt Count")
    public int requestAttemptCount;

    @Label("Request Attempt Hash")
    public String requestAttemptHash;

    @Label("Terminal Outcome")
    public String terminalOutcome;
}
