package com.intellidesk.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolExecutionContext")
class ToolExecutionContextTest {

    @Test
    @DisplayName("is a record with all fields accessible")
    void recordFieldsAccessible() {
        ToolExecutionContext ctx = new ToolExecutionContext(1L, 100L, 200L, "trace-abc");

        assertThat(ctx.authenticatedUserId()).isEqualTo(1L);
        assertThat(ctx.workspaceId()).isEqualTo(100L);
        assertThat(ctx.conversationId()).isEqualTo(200L);
        assertThat(ctx.traceId()).isEqualTo("trace-abc");
    }

    @Test
    @DisplayName("equals works correctly")
    void equalsWorks() {
        ToolExecutionContext ctx1 = new ToolExecutionContext(1L, 100L, 200L, "trace");
        ToolExecutionContext ctx2 = new ToolExecutionContext(1L, 100L, 200L, "trace");

        assertThat(ctx1).isEqualTo(ctx2);
    }

    @Test
    @DisplayName("different values are not equal")
    void differentValuesNotEqual() {
        ToolExecutionContext ctx1 = new ToolExecutionContext(1L, 100L, 200L, "trace-a");
        ToolExecutionContext ctx2 = new ToolExecutionContext(2L, 100L, 200L, "trace-a");

        assertThat(ctx1).isNotEqualTo(ctx2);
    }

    @Test
    @DisplayName("hashCode is consistent")
    void hashCodeConsistent() {
        ToolExecutionContext ctx1 = new ToolExecutionContext(1L, 100L, 200L, "trace");
        ToolExecutionContext ctx2 = new ToolExecutionContext(1L, 100L, 200L, "trace");

        assertThat(ctx1.hashCode()).isEqualTo(ctx2.hashCode());
    }
}