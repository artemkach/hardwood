/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IoTraceCommandTest {

    private String fixture(String name) {
        return getClass().getResource("/" + name).getPath();
    }

    @Test
    void rendersPlanTraceAndSummary() {
        Cli.Result result = Cli.launch("iotrace", "-f", fixture("page_index_test.parquet"));

        assertThat(result.exitCode()).isZero();
        // 3 back-to-back columns fuse into one node.
        assertThat(result.output()).contains("Plan 0");
        assertThat(result.output()).contains("3 requirements (fused)");
        assertThat(result.output()).contains("Conformance: OK");
        assertThat(result.output()).contains("status         SUPPORTED");
        assertThat(result.output()).contains("over-fetch 0 B");
    }

    @Test
    void gapOverrideForcesSplitPlan() {
        Cli.Result result = Cli.launch("iotrace", "-f", fixture("page_index_test.parquet"),
                "--max-gap", "-1");

        assertThat(result.exitCode()).isZero();
        // One standalone node per column instead of a fused region.
        assertThat(result.output()).contains("rg0/col0");
        assertThat(result.output()).contains("rg0/col2");
        assertThat(result.output()).doesNotContain("(fused)");
        assertThat(result.output()).contains("(override)");
    }

    @Test
    void multiRowGroupFileYieldsOnePlanPerRowGroup() {
        Cli.Result result = Cli.launch("iotrace", "-f", fixture("filter_pushdown_int.parquet"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("Plan 0");
        assertThat(result.output()).contains("Plan 1");
        assertThat(result.output()).contains("Plan 2");
        assertThat(result.output()).contains("3 plan(s)");
    }

    @Test
    void headTruncationRendersIncomplete() {
        Cli.Result result = Cli.launch("iotrace", "-f", fixture("page_index_test.parquet"),
                "-n", "5");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("INCOMPLETE");
        assertThat(result.output()).contains("Conformance: OK");
    }

    @Test
    void traceOnlySkipsPlans() {
        Cli.Result result = Cli.launch("iotrace", "-f", fixture("page_index_test.parquet"),
                "--trace-only");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).doesNotContain("Plan 0");
        assertThat(result.output()).contains("Trace (");
    }

    @Test
    void missingFileFails() {
        Cli.Result result = Cli.launch("iotrace", "-f", "nonexistent.parquet");

        assertThat(result.exitCode()).isNotZero();
    }
}
