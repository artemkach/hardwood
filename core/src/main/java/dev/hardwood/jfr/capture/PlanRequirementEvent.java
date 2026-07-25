/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.jfr.capture;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// One first-read requirement, materialized into a final node. A fused node
/// carries N of these (MATERIALIZES_INTO — distinct from a scheduling edge, so
/// per-subrange records are never conflated with dependencies).
@Name("dev.hardwood.capture.PlanRequirement")
@Label("Plan Requirement")
@Category({"Hardwood", "Capture"})
@Description("A first-read requirement materialized into a final request node")
@Enabled(false)
@StackTrace(false)
public class PlanRequirementEvent extends Event {

    @Label("Execution ID")
    public long executionId;

    @Label("Plan ID")
    public long planId;

    @Label("Requirement ID")
    public long requirementId;

    @Label("Request Node ID")
    public long requestNodeId;

    @Label("Offset")
    public long offset;

    @Label("Length")
    @DataAmount
    public int length;

    @Label("Column Role")
    public String columnRole;
}
