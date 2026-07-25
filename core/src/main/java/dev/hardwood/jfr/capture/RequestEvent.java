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
import jdk.jfr.Timespan;

/// One executed request attempt = one invocation of a final request object's
/// `readRange()`. Committed on both success and failure so lost or defective
/// attempts are represented. Timing fields carry the attempt's wall-clock cost
/// but are excluded from the execution seal's structural hash.
@Name("dev.hardwood.capture.Request")
@Label("Request Attempt")
@Category({"Hardwood", "Capture"})
@Description("One executed readRange attempt against a captured plan node")
@Enabled(false)
@StackTrace(false)
public class RequestEvent extends Event {

    @Label("Execution ID")
    public long executionId;

    @Label("Plan ID")
    public long planId;

    @Label("Node ID")
    public long nodeId;

    @Label("Attempt ID")
    public long attemptId;

    @Label("Actual Offset")
    public long actualOffset;

    @Label("Actual Length")
    @DataAmount
    public int actualLength;

    @Label("Stage")
    public String stage;

    @Label("Outcome")
    public String outcome;

    @Label("Begin Nanos")
    public long beginNanos;

    @Label("Duration")
    @Timespan(Timespan.NANOSECONDS)
    public long durationNanos;
}
