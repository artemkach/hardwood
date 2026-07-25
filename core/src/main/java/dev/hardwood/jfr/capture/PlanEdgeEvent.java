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

/// A scheduling dependency between two final nodes. v0 graphs are edge-free;
/// the event exists so a later graph with real dependencies needs no schema
/// change.
@Name("dev.hardwood.capture.PlanEdge")
@Label("Plan Edge")
@Category({"Hardwood", "Capture"})
@Description("A scheduling dependency between two final request nodes")
@Enabled(false)
@StackTrace(false)
public class PlanEdgeEvent extends Event {

    @Label("Execution ID")
    public long executionId;

    @Label("Plan ID")
    public long planId;

    @Label("From Node ID")
    public long fromNodeId;

    @Label("To Node ID")
    public long toNodeId;

    @Label("Edge Kind")
    public String edgeKind;
}
