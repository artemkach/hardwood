/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.jfr.iotrace;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// A final post-coalescing request node in a captured static fetch plan.
///
/// Part of the default-disabled plan-capture event family. `@Enabled(false)`
/// keeps it inert whenever an unrelated production recording is active — only
/// an explicit capture recording turns the whole family on. `@StackTrace(false)`
/// keeps the disabled and enabled paths cheap.
///
/// All fields are flat primitives or strings: JFR event payloads do not
/// portably carry records, enums, arrays, or collections.
@Name("dev.hardwood.iotrace.PlanNode")
@Label("Plan Node")
@Category({"Hardwood", "IO Trace"})
@Description("A final post-coalescing request node in a captured static fetch plan")
@Enabled(false)
@StackTrace(false)
public class PlanNodeEvent extends Event {

    @Label("Execution ID")
    public long executionId;

    @Label("Plan ID")
    public long planId;

    @Label("Node ID")
    public long nodeId;

    @Label("Offset")
    public long offset;

    @Label("Length")
    @DataAmount
    public int length;

    @Label("Stage")
    public String stage;

    @Label("Role")
    public String role;
}
