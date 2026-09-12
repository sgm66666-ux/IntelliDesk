package com.intellidesk.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("IntelliDeskToolCallback")
class IntelliDeskToolCallbackTest {

    private ToolExecutor toolExecutor;
    private ToolRegistry toolRegistry;
    private ToolExecutionContext ctx;
    private AgentToolTraceCollector collector;
    private AgentToolDescriptor descriptor;

    @BeforeEach
    void setUp() {
        toolExecutor = mock(ToolExecutor.class);
        toolRegistry = mock(ToolRegistry.class);
        ctx = new ToolExecutionContext(1L, 100L, 200L, "trace-001");
        collector = new AgentToolTraceCollector();
        descriptor = new AgentToolDescriptor("test_tool", "A test tool", "{\"type\":\"object\"}");
    }

    @Nested
    @DisplayName("normal execution")
    class NormalExecutionTests {

        @Test
        @DisplayName("executes tool and returns content on success")
        void executesToolAndReturnsContent() {
            when(toolExecutor.execute(eq("test_tool"), any(), eq(ctx)))
                    .thenReturn(AgentToolExecutionResult.success("result content", Map.of("count", 3)));

            IntelliDeskToolCallback callback = new IntelliDeskToolCallback(descriptor, toolExecutor);
            ToolContext toolContext = new ToolContext(Map.of("ctx", ctx, "collector", collector));

            String result = callback.call("{\"query\":\"test\"}", toolContext);

            assertThat(result).isEqualTo("result content");
            assertThat(collector.size()).isEqualTo(1);
            AgentToolTraceCollector.ToolTraceEntry entry = collector.getEntries().get(0);
            assertThat(entry.toolName()).isEqualTo("test_tool");
            assertThat(entry.result().success()).isTrue();
            assertThat(entry.durationMs()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("returns error message on tool failure")
        void returnsErrorMessageOnFailure() {
            when(toolExecutor.execute(eq("test_tool"), any(), eq(ctx)))
                    .thenReturn(AgentToolExecutionResult.failure("ERR", "something went wrong"));

            IntelliDeskToolCallback callback = new IntelliDeskToolCallback(descriptor, toolExecutor);
            ToolContext toolContext = new ToolContext(Map.of("ctx", ctx, "collector", collector));

            String result = callback.call("{}", toolContext);

            assertThat(result).startsWith("Error: ");
            assertThat(result).contains("something went wrong");
            assertThat(collector.size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("tool context handling")
    class ToolContextHandlingTests {

        @Test
        @DisplayName("propagates ctx and collector from ToolContext")
        void propagatesContextAndCollector() {
            when(toolExecutor.execute(eq("test_tool"), any(), eq(ctx)))
                    .thenReturn(AgentToolExecutionResult.success("ok"));

            IntelliDeskToolCallback callback = new IntelliDeskToolCallback(descriptor, toolExecutor);
            ToolContext toolContext = new ToolContext(Map.of("ctx", ctx, "collector", collector));

            callback.call("{}", toolContext);

            verify(toolExecutor).execute(eq("test_tool"), any(), eq(ctx));
            assertThat(collector.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("handles null collector gracefully")
        void handlesNullCollector() {
            when(toolExecutor.execute(eq("test_tool"), any(), eq(ctx)))
                    .thenReturn(AgentToolExecutionResult.success("ok"));

            IntelliDeskToolCallback callback = new IntelliDeskToolCallback(descriptor, toolExecutor);
            ToolContext toolContext = new ToolContext(Map.of("ctx", ctx));

            String result = callback.call("{}", toolContext);

            assertThat(result).isEqualTo("ok");
        }

        @Test
        @DisplayName("handles missing ctx in ToolContext")
        void handlesMissingCtx() {
            IntelliDeskToolCallback callback = new IntelliDeskToolCallback(descriptor, toolExecutor);
            ToolContext toolContext = new ToolContext(Map.of());

            String result = callback.call("{}", toolContext);

            // Should not throw; returns error
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("call without ToolContext")
    class CallWithoutToolContextTests {

        @Test
        @DisplayName("call(String) without ToolContext returns error")
        void callWithoutToolContextReturnsError() {
            IntelliDeskToolCallback callback = new IntelliDeskToolCallback(descriptor, toolExecutor);

            String result = callback.call("{}");

            assertThat(result).contains("requires context");
        }
    }

    @Nested
    @DisplayName("argument parsing")
    class ArgumentParsingTests {

        @Test
        @DisplayName("parses valid JSON arguments")
        void parsesValidJson() {
            when(toolExecutor.execute(eq("test_tool"), any(), eq(ctx)))
                    .thenAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> args = inv.getArgument(1);
                        assertThat(args).containsEntry("query", "hello");
                        assertThat(args).containsEntry("topK", 5);
                        return AgentToolExecutionResult.success("ok");
                    });

            IntelliDeskToolCallback callback = new IntelliDeskToolCallback(descriptor, toolExecutor);
            ToolContext toolContext = new ToolContext(Map.of("ctx", ctx, "collector", collector));

            callback.call("{\"query\":\"hello\",\"topK\":5}", toolContext);
        }

        @Test
        @DisplayName("handles null input")
        void handlesNullInput() {
            when(toolExecutor.execute(eq("test_tool"), any(), eq(ctx)))
                    .thenReturn(AgentToolExecutionResult.success("ok"));

            IntelliDeskToolCallback callback = new IntelliDeskToolCallback(descriptor, toolExecutor);
            ToolContext toolContext = new ToolContext(Map.of("ctx", ctx, "collector", collector));

            String result = callback.call(null, toolContext);

            assertThat(result).isEqualTo("ok");
        }

        @Test
        @DisplayName("handles blank input")
        void handlesBlankInput() {
            when(toolExecutor.execute(eq("test_tool"), any(), eq(ctx)))
                    .thenReturn(AgentToolExecutionResult.success("ok"));

            IntelliDeskToolCallback callback = new IntelliDeskToolCallback(descriptor, toolExecutor);
            ToolContext toolContext = new ToolContext(Map.of("ctx", ctx, "collector", collector));

            String result = callback.call("   ", toolContext);

            assertThat(result).isEqualTo("ok");
        }

        @Test
        @DisplayName("handles invalid JSON gracefully")
        void handlesInvalidJson() {
            when(toolExecutor.execute(eq("test_tool"), any(), eq(ctx)))
                    .thenReturn(AgentToolExecutionResult.success("ok"));

            IntelliDeskToolCallback callback = new IntelliDeskToolCallback(descriptor, toolExecutor);
            ToolContext toolContext = new ToolContext(Map.of("ctx", ctx, "collector", collector));

            String result = callback.call("not-json", toolContext);

            assertThat(result).isEqualTo("ok");
        }
    }

    @Nested
    @DisplayName("getToolDefinition")
    class GetToolDefinitionTests {

        @Test
        @DisplayName("returns correct tool definition")
        void returnsCorrectToolDefinition() {
            IntelliDeskToolCallback callback = new IntelliDeskToolCallback(descriptor, toolExecutor);

            var def = callback.getToolDefinition();

            assertThat(def.name()).isEqualTo("test_tool");
            assertThat(def.description()).isEqualTo("A test tool");
            assertThat(def.inputSchema()).isEqualTo("{\"type\":\"object\"}");
        }
    }

    @Nested
    @DisplayName("buildCallbacks via ToolCallbackFactory")
    class BuildCallbacksTests {

        @Test
        @DisplayName("Factory builds callbacks from ToolRegistry without request state")
        void factoryBuildsCallbacksFromRegistry() {
            AgentToolDescriptor desc1 = new AgentToolDescriptor("tool1", "desc1", "{}");
            AgentToolDescriptor desc2 = new AgentToolDescriptor("tool2", "desc2", "{}");
            when(toolRegistry.listDescriptors()).thenReturn(List.of(desc1, desc2));

            ToolCallbackFactory factory = new ToolCallbackFactory(toolExecutor);
            List<org.springframework.ai.tool.ToolCallback> callbacks = factory.buildCallbacks(toolRegistry);

            assertThat(callbacks).hasSize(2);
            assertThat(callbacks.get(0).getToolDefinition().name()).isEqualTo("tool1");
            assertThat(callbacks.get(1).getToolDefinition().name()).isEqualTo("tool2");
        }

        @Test
        @DisplayName("Factory is stateless — no ctx, collector, or request params")
        void factoryIsStateless() {
            ToolCallbackFactory factory = new ToolCallbackFactory(toolExecutor);
            List<org.springframework.ai.tool.ToolCallback> callbacks = factory.buildCallbacks(toolRegistry);

            assertThat(callbacks).isNotNull();
        }

        @Test
        @DisplayName("Callback built by factory can execute tool")
        void callbackBuiltByFactoryCanExecute() {
            AgentToolDescriptor desc = new AgentToolDescriptor("test", "desc", "{}");
            when(toolRegistry.listDescriptors()).thenReturn(List.of(desc));
            when(toolExecutor.execute(eq("test"), any(), eq(ctx)))
                    .thenReturn(AgentToolExecutionResult.success("ok"));

            ToolCallbackFactory factory = new ToolCallbackFactory(toolExecutor);
            List<org.springframework.ai.tool.ToolCallback> callbacks = factory.buildCallbacks(toolRegistry);

            assertThat(callbacks).hasSize(1);
            ToolContext tc = new ToolContext(Map.of("ctx", ctx, "collector", collector));
            String result = callbacks.get(0).call("{}", tc);
            assertThat(result).isEqualTo("ok");
        }
    }

    @Nested
    @DisplayName("duration tracking")
    class DurationTrackingTests {

        @Test
        @DisplayName("records positive duration")
        void recordsPositiveDuration() {
            when(toolExecutor.execute(eq("test_tool"), any(), eq(ctx)))
                    .thenReturn(AgentToolExecutionResult.success("ok"));

            IntelliDeskToolCallback callback = new IntelliDeskToolCallback(descriptor, toolExecutor);
            ToolContext toolContext = new ToolContext(Map.of("ctx", ctx, "collector", collector));

            callback.call("{}", toolContext);

            assertThat(collector.size()).isEqualTo(1);
            assertThat(collector.getEntries().get(0).durationMs()).isGreaterThanOrEqualTo(0);
        }
    }
}