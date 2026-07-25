/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.jfr.capture;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// The plan seal, emitted at the plan-publication quiescence point (the
/// `coalesceAcrossColumns()` return, for the one-row-group v0). Detects loss
/// or mutation of plan records via the count fields and `planHash`. `status`
/// distinguishes a supported plan from an incomplete one, so an unsupported
/// plan is not confused with recording loss.
@Name("dev.hardwood.capture.PlanSealed")
@Label("Plan Sealed")
@Category({"Hardwood", "Capture"})
@Description("Plan-publication seal with counts and hash for loss detection")
@Enabled(false)
@StackTrace(false)
public class PlanSealedEvent extends Event {

    @Label("Execution ID")
    public long executionId;

    @Label("Plan ID")
    public long planId;

    @Label("Node Count")
    public int nodeCount;

    @Label("Requirement Count")
    public int requirementCount;

    @Label("Edge Count")
    public int edgeCount;

    @Label("Plan Hash")
    public String planHash;

    @Label("Status")
    public String status;

    @Label("Reason")
    public String reason;
}
