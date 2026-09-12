package com.intellidesk.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgentToolTraceCollector")
class AgentToolTraceCollectorTest {

    private final AgentToolTraceCollector collector = new AgentToolTraceCollector();

    @Nested
    @DisplayName("empty collector")
    class EmptyCollectorTests {

        @Test
        @DisplayName("isEmpty returns true initially")
        void isEmptyInitially() {
            assertThat(collector.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("size returns zero initially")
        void sizeIsZeroInitially() {
            assertThat(collector.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("getEntries returns empty list initially")
        void getEntriesEmptyInitially() {
            assertThat(collector.getEntries()).isEmpty();
        }
    }

    @Nested
    @DisplayName("single record")
    class SingleRecordTests {

        @Test
        @DisplayName("records a single tool execution")
        void recordsSingleEntry() {
            AgentToolExecutionResult result = AgentToolExecutionResult.success("ok");
            collector.record("test_tool", Map.of("key", "value"), result, 150L);

            assertThat(collector.size()).isEqualTo(1);
            assertThat(collector.isEmpty()).isFalse();

            var entry = collector.getEntries().get(0);
            assertThat(entry.toolName()).isEqualTo("test_tool");
            assertThat(entry.arguments()).containsEntry("key", "value");
            assertThat(entry.result()).isSameAs(result);
            assertThat(entry.durationMs()).isEqualTo(150L);
        }
    }

    @Nested
    @DisplayName("multiple records")
    class MultipleRecordsTests {

        @Test
        @DisplayName("records multiple tool executions in order")
        void recordsMultipleEntriesInOrder() {
            collector.record("tool_a", Map.of(), AgentToolExecutionResult.success("a"), 100L);
            collector.record("tool_b", Map.of(), AgentToolExecutionResult.success("b"), 200L);
            collector.record("tool_c", Map.of(), AgentToolExecutionResult.failure("ERR", "fail"), 50L);

            var entries = collector.getEntries();
            assertThat(entries).hasSize(3);
            assertThat(entries.get(0).toolName()).isEqualTo("tool_a");
            assertThat(entries.get(1).toolName()).isEqualTo("tool_b");
            assertThat(entries.get(2).toolName()).isEqualTo("tool_c");
            assertThat(entries.get(2).result().success()).isFalse();
        }
    }

    @Nested
    @DisplayName("immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("getEntries returns unmodifiable list")
        void returnsUnmodifiableList() {
            collector.record("tool_a", Map.of(), AgentToolExecutionResult.success("ok"), 100L);

            assertThatThrownBy(() -> collector.getEntries().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("argument defensive copy")
    class ArgumentDefensiveCopyTests {

        @Test
        @DisplayName("modifying original arguments map after record does not affect trace")
        void modifyingOriginalMapDoesNotAffectTrace() {
            Map<String, Object> mutable = new HashMap<>();
            mutable.put("key", "original");
            collector.record("tool_x", mutable, AgentToolExecutionResult.success("ok"), 100L);

            mutable.put("key", "modified");
            mutable.put("extra", "injected");

            var entry = collector.getEntries().get(0);
            assertThat(entry.arguments()).containsEntry("key", "original");
            assertThat(entry.arguments()).doesNotContainKey("extra");
        }

        @Test
        @DisplayName("trace entry arguments is unmodifiable")
        void traceEntryArgumentsIsUnmodifiable() {
            collector.record("tool_x", Map.of("key", "value"), AgentToolExecutionResult.success("ok"), 100L);

            var entry = collector.getEntries().get(0);
            assertThatThrownBy(() -> entry.arguments().put("hack", "bad"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("concurrency safety")
    class ConcurrencySafetyTests {

        @Test
        @DisplayName("concurrent record does not break collector")
        void concurrentRecordDoesNotBreak() throws Exception {
            int threads = 10;
            int recordsPerThread = 100;
            CountDownLatch latch = new CountDownLatch(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                final int threadId = i;
                pool.submit(() -> {
                    try {
                        for (int j = 0; j < recordsPerThread; j++) {
                            collector.record("tool_" + threadId + "_" + j,
                                    Map.of("thread", threadId),
                                    AgentToolExecutionResult.success("ok"),
                                    System.currentTimeMillis());
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            pool.shutdown();

            assertThat(collector.size()).isEqualTo(threads * recordsPerThread);
        }
    }
}