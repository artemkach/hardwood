/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.hardwood.cli.internal.Sizes;
import dev.hardwood.cli.internal.table.RowTable;
import dev.hardwood.internal.capture.CaptureSchema;
import dev.hardwood.internal.capture.PlanConformance;
import dev.hardwood.internal.capture.StaticFetchPlan;
import dev.hardwood.internal.capture.TracingInputFile;

/// Renders captured [StaticFetchPlan]s and the correlated seam trace as
/// plain text: a byte-layout map per plan (the visual anchor), the
/// arrival-ordered trace with each read matched to its node, and a summary
/// block. Pure string building over immutable inputs, so it is unit-testable
/// against hand-built plans without touching a file.
final class IotraceRenderer {

    /// Width of the layout bar in characters.
    private static final int BAR_WIDTH = 64;

    private static final char FILL_USEFUL = '█';   // █ bytes some requirement needs
    private static final char FILL_DEAD = '░';     // ░ dead gap bytes inside a fused node
    private static final char FILL_UNPLANNED = ' ';     // bytes no node fetches

    private IotraceRenderer() {
    }

    /// Renders one plan: header, layout map, node table.
    static String renderPlan(StaticFetchPlan plan) {
        StringBuilder sb = new StringBuilder();
        List<StaticFetchPlan.Node> nodes = plan.nodesSorted();

        sb.append("Plan ").append(plan.planId());
        if (!plan.isSupported()) {
            sb.append("  [INCOMPLETE: ").append(plan.reason()).append(']');
        }
        sb.append('\n');

        if (nodes.isEmpty()) {
            sb.append("  (no data-stage nodes published)\n");
            return sb.toString();
        }

        long extentStart = nodes.get(0).offset();
        long extentEnd = nodes.get(nodes.size() - 1).offset() + nodes.get(nodes.size() - 1).length();
        sb.append("  bytes [").append(extentStart).append(" .. ").append(extentEnd)
                .append(")  (").append(Sizes.format(extentEnd - extentStart)).append(")\n");

        sb.append("  ").append(renderBar(plan, extentStart, extentEnd)).append('\n');

        String[] headers = {"Node", "Stage", "Range", "Length", "Serves"};
        List<String[]> rows = new ArrayList<>();
        for (StaticFetchPlan.Node node : nodes) {
            long reqs = plan.requirements().stream()
                    .filter(r -> r.requestNodeId() == node.nodeId()).count();
            String serves = reqs == 1 ? node.role()
                    : reqs + " requirements (fused)";
            rows.add(new String[]{
                    Long.toString(node.nodeId()),
                    node.stage(),
                    "[" + node.offset() + " .. " + (node.offset() + node.length()) + ")",
                    Sizes.format(node.length()),
                    serves
            });
        }
        sb.append(indent(RowTable.renderTable(headers, rows))).append('\n');
        return sb.toString();
    }

    /// Renders the layout bar: each position maps to a byte range of the
    /// plan's extent; `█` where a requirement needs the byte, `░` where a
    /// node fetches dead bytes, space where nothing is fetched.
    static String renderBar(StaticFetchPlan plan, long extentStart, long extentEnd) {
        long extent = extentEnd - extentStart;
        if (extent <= 0) {
            return "";
        }
        char[] bar = new char[BAR_WIDTH];
        for (int i = 0; i < BAR_WIDTH; i++) {
            long posStart = extentStart + extent * i / BAR_WIDTH;
            long posEnd = extentStart + extent * (i + 1) / BAR_WIDTH;
            if (posEnd == posStart) {
                posEnd = posStart + 1;
            }
            bar[i] = classify(plan, posStart, posEnd);
        }
        return new String(bar);
    }

    /// Classification for one bar cell covering `[start, end)`: useful wins
    /// over dead wins over unplanned, so narrow requirements stay visible.
    private static char classify(StaticFetchPlan plan, long start, long end) {
        boolean fetched = false;
        for (StaticFetchPlan.Requirement req : plan.requirements()) {
            if (req.offset() < end && start < req.offset() + req.length()) {
                return FILL_USEFUL;
            }
        }
        for (StaticFetchPlan.Node node : plan.nodes()) {
            if (node.offset() < end && start < node.offset() + node.length()) {
                fetched = true;
                break;
            }
        }
        return fetched ? FILL_DEAD : FILL_UNPLANNED;
    }

    /// Renders the arrival-ordered trace with node correlation and the
    /// conformance verdict.
    static String renderTrace(List<TracingInputFile.TracedRead> trace,
                              PlanConformance.Result conformance,
                              boolean remote) {
        StringBuilder sb = new StringBuilder();
        sb.append("Trace (").append(trace.size()).append(" reads at the InputFile seam, arrival order)\n");
        if (remote) {
            sb.append("  note: remote input — the footer is served from the open() suffix-range\n")
                    .append("  tail fetch, which is internal to S3InputFile and invisible at this seam\n");
        }

        String[] headers = {"#", "Offset", "Length", "Matched"};
        List<String[]> rows = new ArrayList<>();
        int i = 1;
        for (TracingInputFile.TracedRead read : trace) {
            rows.add(new String[]{
                    Integer.toString(i++),
                    Long.toString(read.offset()),
                    Sizes.format(read.length()),
                    label(read, conformance)
            });
        }
        sb.append(indent(RowTable.renderTable(headers, rows))).append('\n');

        if (conformance.isConformant()) {
            sb.append("Conformance: OK — every plan node executed exactly once; ")
                    .append(conformance.unmatchedReads().size())
                    .append(" read(s) outside the data-stage plan (metadata stage).\n");
        }
        else {
            sb.append("Conformance: FAILED\n");
            for (StaticFetchPlan.Node node : conformance.missing()) {
                sb.append("  missing execution for node ").append(node.nodeId())
                        .append(" [").append(node.offset()).append(" .. ")
                        .append(node.offset() + node.length()).append(")\n");
            }
            for (StaticFetchPlan.Node node : conformance.duplicated()) {
                sb.append("  duplicate execution of node ").append(node.nodeId()).append('\n');
            }
        }
        return sb.toString();
    }

    private static String label(TracingInputFile.TracedRead read, PlanConformance.Result conformance) {
        for (PlanConformance.MatchedNode m : conformance.matched()) {
            if (m.read().equals(read)) {
                return "node " + m.node().nodeId();
            }
        }
        return "metadata";
    }

    /// Renders the file-level summary across all plans.
    static String renderSummary(long executionId, List<StaticFetchPlan> plans, int gapSetting) {
        long useful = plans.stream().mapToLong(StaticFetchPlan::usefulBytes).sum();
        long fetched = plans.stream().mapToLong(StaticFetchPlan::fetchedBytes).sum();
        long overFetch = fetched - useful;
        long incomplete = plans.stream().filter(p -> !p.isSupported()).count();

        StringBuilder sb = new StringBuilder();
        sb.append("Summary (execution ").append(executionId).append(", ")
                .append(plans.size()).append(" plan(s))\n");
        sb.append("  status         ");
        if (incomplete == 0) {
            sb.append(CaptureSchema.STATUS_SUPPORTED);
        }
        else {
            sb.append(incomplete).append(" of ").append(plans.size())
                    .append(" plans ").append(CaptureSchema.STATUS_INCOMPLETE);
        }
        sb.append('\n');
        long nodes = plans.stream().mapToLong(p -> p.nodes().size()).sum();
        long requirements = plans.stream().mapToLong(p -> p.requirements().size()).sum();
        sb.append("  nodes          ").append(nodes)
                .append("    requirements ").append(requirements).append('\n');
        sb.append("  useful bytes   ").append(Sizes.format(useful))
                .append("    fetched ").append(Sizes.format(fetched))
                .append("    over-fetch ").append(Sizes.format(overFetch));
        if (fetched > 0) {
            sb.append(String.format(" (%.1f%%)", 100.0 * overFetch / fetched));
        }
        sb.append('\n');
        sb.append("  gap setting    ").append(gapSetting).append(" bytes")
                .append(gapSetting == 64 * 1024 ? " (default)" : " (override)").append('\n');
        return sb.toString();
    }

    private static String indent(String block) {
        return "  " + block.replace("\n", "\n  ").stripTrailing();
    }

    /// Sorts plans for display (by plan ID, i.e. row-group order).
    static List<StaticFetchPlan> displayOrder(List<StaticFetchPlan> plans) {
        List<StaticFetchPlan> sorted = new ArrayList<>(plans);
        sorted.sort(Comparator.comparingLong(StaticFetchPlan::planId));
        return sorted;
    }
}
