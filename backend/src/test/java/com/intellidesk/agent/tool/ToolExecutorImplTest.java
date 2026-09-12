package com.intellidesk.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolExecutorImpl")
class ToolExecutorImplTest {

    private ToolRegistryImpl registry;
    private ToolExecutorImpl executor;
    private ToolResultSanitizer sanitizer;
    private ObjectMapper objectMapper;
    private Validator validator;
    private ThreadPoolTaskExecutor taskExecutor;
    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistryImpl();
        objectMapper = new ObjectMapper();
        try (ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
        AgentProperties props = new AgentProperties();
        props.setToolTimeoutSeconds(3);
        props.setToolMaxResultChars(16384);
        props.setToolCorePoolSize(2);
        props.setToolMaxPoolSize(4);
        props.setToolQueueCapacity(8);
        sanitizer = new ToolResultSanitizer(props);

        taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(2);
        taskExecutor.setMaxPoolSize(4);
        taskExecutor.setQueueCapacity(8);
        taskExecutor.setThreadNamePrefix("test-agent-tool-");
        taskExecutor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        taskExecutor.initialize();

        executor = new ToolExecutorImpl(registry, objectMapper, validator, sanitizer, props, taskExecutor);
        ctx = new ToolExecutionContext(1L, 100L, 200L, "trace-001");
    }

    @AfterEach
    void tearDown() {
        taskExecutor.shutdown();
    }

    @Nested
    @DisplayName("normal execution")
    class NormalExecutionTests {

        @Test
        @DisplayName("executes a registered tool and returns success")
        void executesToolSuccessfully() {
            registry.register(createSimpleTool("test_tool", ctxRef -> AgentToolExecutionResult.success("hello from tool")));

            AgentToolExecutionResult result = executor.execute("test_tool", Map.of(), ctx);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("hello from tool");
            assertThat(result.errorCode()).isNull();
        }

        @Test
        @DisplayName("executes tool with arguments and metadata")
        void executesWithArgumentsAndMetadata() {
            @SuppressWarnings("unchecked")
            AgentTool<Map> echoTool = new AgentTool<>() {
                @Override public String name() { return "echo"; }
                @Override public String description() { return "echo"; }
                @Override public String parametersSchema() { return "{\"type\":\"object\"}"; }
                @Override public Class<Map> argumentType() { return Map.class; }
                @Override public AgentToolExecutionResult execute(ToolExecutionContext ctx, Map args) {
                    return AgentToolExecutionResult.success("echo: " + args.get("message"), Map.of("count", 1));
                }
            };
            registry.register(echoTool);

            AgentToolExecutionResult result = executor.execute("echo", Map.of("message", "hi"), ctx);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("echo: hi");
            assertThat(result.metadata()).containsEntry("count", 1);
        }

        @Test
        @DisplayName("context is propagated to tool")
        void contextIsPropagated() {
            AtomicBoolean ctxReceived = new AtomicBoolean(false);
            registry.register(createSimpleTool("ctx_tool", ctxRef -> {
                ctxReceived.set(ctxRef != null
                        && ctxRef.authenticatedUserId().equals(1L)
                        && ctxRef.workspaceId().equals(100L));
                return AgentToolExecutionResult.success("ok");
            }));

            executor.execute("ctx_tool", Map.of(), ctx);
            assertThat(ctxReceived).isTrue();
        }
    }

    @Nested
    @DisplayName("unknown tool")
    class UnknownToolTests {

        @Test
        @DisplayName("returns failure for unknown tool name")
        void returnsFailureForUnknown() {
            AgentToolExecutionResult result = executor.execute("no_such_tool", Map.of(), ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("TOOL_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("argument type conversion")
    class ArgumentTypeConversionTests {

        @Test
        @DisplayName("returns failure when arguments cannot be converted")
        void returnsFailureOnTypeMismatch() {
            @SuppressWarnings("unchecked")
            AgentTool<String> typedTool = new AgentTool<>() {
                @Override public String name() { return "typed_tool"; }
                @Override public String description() { return "typed"; }
                @Override public String parametersSchema() { return "{\"type\":\"object\"}"; }
                @Override public Class<String> argumentType() { return String.class; }
                @Override public AgentToolExecutionResult execute(ToolExecutionContext ctx, String args) {
                    return AgentToolExecutionResult.success("ok");
                }
            };
            registry.register(typedTool);

            AgentToolExecutionResult result = executor.execute("typed_tool", Map.of("not", "string"), ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("ARGUMENT_TYPE_MISMATCH");
            assertThat(result.errorMessage()).doesNotContain("Cannot construct");
        }
    }

    @Nested
    @DisplayName("argumentType null defensive")
    class ArgumentTypeNullTests {

        @Test
        @DisplayName("fails closed when argumentType is null and registry missed it")
        void failsClosedOnNullArgumentType() {
            // Simulate a tool that somehow got registered with null argumentType
            // (bypassing registry validation for this test)
            AgentTool<?> badTool = new AgentTool<>() {
                @Override public String name() { return "bad_tool"; }
                @Override public String description() { return "bad"; }
                @Override public String parametersSchema() { return "{\"type\":\"object\"}"; }
                @Override public Class<Object> argumentType() { return null; }
                @Override public AgentToolExecutionResult execute(ToolExecutionContext ctx, Object args) {
                    return AgentToolExecutionResult.success("ok");
                }
            };
            // Bypass registry validation to test executor defensive check
            java.lang.reflect.Field toolsField;
            try {
                toolsField = ToolRegistryImpl.class.getDeclaredField("tools");
                toolsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<String, AgentTool<?>> tools = (java.util.Map<String, AgentTool<?>>) toolsField.get(registry);
                tools.put("bad_tool", badTool);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            AgentToolExecutionResult result = executor.execute("bad_tool", Map.of(), ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("TOOL_CONFIGURATION_ERROR");
        }
    }

    @Nested
    @DisplayName("argument validation")
    class ArgumentValidationTests {

        @Test
        @DisplayName("fails on @NotBlank violation")
        void failsOnNotBlank() {
            registerDtoTool("validated_tool", ValidatedDto.class, (ctxRef, dto) -> AgentToolExecutionResult.success("ok"));
            AgentToolExecutionResult result = executor.execute("validated_tool", Map.of("name", "", "count", 5), ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("ARGUMENT_VALIDATION_FAILED");
            assertThat(result.errorMessage()).contains("name");
        }

        @Test
        @DisplayName("fails on @Size violation")
        void failsOnSize() {
            registerDtoTool("validated_tool", ValidatedDto.class, (ctxRef, dto) -> AgentToolExecutionResult.success("ok"));
            AgentToolExecutionResult result = executor.execute("validated_tool", Map.of("name", "ab", "count", 5), ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("ARGUMENT_VALIDATION_FAILED");
        }

        @Test
        @DisplayName("fails on @Min violation")
        void failsOnMin() {
            registerDtoTool("validated_tool", ValidatedDto.class, (ctxRef, dto) -> AgentToolExecutionResult.success("ok"));
            AgentToolExecutionResult result = executor.execute("validated_tool", Map.of("name", "hello", "count", -1), ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("ARGUMENT_VALIDATION_FAILED");
        }

        @Test
        @DisplayName("validation failure does not execute tool")
        void validationFailureDoesNotExecuteTool() {
            AtomicBoolean executed = new AtomicBoolean(false);
            registerDtoTool("validated_tool", ValidatedDto.class, (ctxRef, dto) -> {
                executed.set(true);
                return AgentToolExecutionResult.success("ok");
            });
            executor.execute("validated_tool", Map.of("name", "", "count", -1), ctx);

            assertThat(executed).isFalse();
        }
    }

    @Nested
    @DisplayName("rawArguments null")
    class RawArgumentsNullTests {

        @Test
        @DisplayName("handles null rawArguments gracefully")
        void handlesNullRawArguments() {
            registry.register(createSimpleTool("no_arg_tool", ctxRef -> AgentToolExecutionResult.success("done")));

            AgentToolExecutionResult result = executor.execute("no_arg_tool", null, ctx);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("done");
        }
    }

    @Nested
    @DisplayName("tool timeout")
    class TimeoutTests {

        @Test
        @DisplayName("returns failure when tool exceeds timeout")
        void returnsFailureOnTimeout() {
            registry.register(createSimpleTool("slow_tool", ctxRef -> {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return AgentToolExecutionResult.success("done");
            }));

            AgentToolExecutionResult result = executor.execute("slow_tool", Map.of(), ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("TOOL_TIMEOUT");
        }

        @Test
        @DisplayName("executor can still execute new tasks after timeout")
        void executorStillWorksAfterTimeout() {
            registry.register(createSimpleTool("slow_tool", ctxRef -> {
                try { Thread.sleep(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return AgentToolExecutionResult.success("done");
            }));
            executor.execute("slow_tool", Map.of(), ctx);

            registry.register(createSimpleTool("fast_tool", ctxRef -> AgentToolExecutionResult.success("fast")));
            AgentToolExecutionResult result = executor.execute("fast_tool", Map.of(), ctx);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("fast");
        }
    }

    @Nested
    @DisplayName("exception isolation")
    class ExceptionIsolationTests {

        @Test
        @DisplayName("returns failure when tool throws ToolException")
        void handlesToolException() {
            registry.register(createSimpleTool("failing_tool", ctxRef -> {
                throw new ToolException("CUSTOM_ERROR", "custom error message");
            }));

            AgentToolExecutionResult result = executor.execute("failing_tool", Map.of(), ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("CUSTOM_ERROR");
            assertThat(result.errorMessage()).isEqualTo("custom error message");
        }

        @Test
        @DisplayName("returns failure when tool throws RuntimeException")
        void handlesRuntimeException() {
            registry.register(createSimpleTool("crashing_tool", ctxRef -> {
                throw new RuntimeException("boom!");
            }));

            AgentToolExecutionResult result = executor.execute("crashing_tool", Map.of(), ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("TOOL_EXECUTION_ERROR");
            assertThat(result.errorMessage()).doesNotContain("boom!");
        }

        @Test
        @DisplayName("raw exception message is not leaked")
        void rawExceptionMessageNotLeaked() {
            registry.register(createSimpleTool("leaky_tool", ctxRef -> {
                throw new RuntimeException("sensitive internal detail");
            }));

            AgentToolExecutionResult result = executor.execute("leaky_tool", Map.of(), ctx);

            assertThat(result.errorMessage()).doesNotContain("sensitive internal detail");
        }

        @Test
        @DisplayName("isolates tool exception from executor")
        void isolatesToolException() {
            AtomicBoolean toolCalled = new AtomicBoolean(false);
            registry.register(createSimpleTool("isolated", ctxRef -> {
                toolCalled.set(true);
                throw new RuntimeException("internal error");
            }));

            AgentToolExecutionResult result = executor.execute("isolated", Map.of(), ctx);

            assertThat(toolCalled).isTrue();
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("TOOL_EXECUTION_ERROR");
        }
    }

    @Nested
    @DisplayName("null result")
    class NullResultTests {

        @Test
        @DisplayName("handles tool returning null")
        void handlesNullResult() {
            registry.register(createSimpleTool("null_tool", ctxRef -> null));

            AgentToolExecutionResult result = executor.execute("null_tool", Map.of(), ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("TOOL_EXECUTION_ERROR");
        }
    }

    @Nested
    @DisplayName("tool failure result sanitization")
    class ToolFailureResultSanitizationTests {

        @Test
        @DisplayName("sanitizes tool failure result")
        void sanitizesToolFailureResult() {
            registry.register(createSimpleTool("fail_tool", ctxRef ->
                    AgentToolExecutionResult.failure("ERR", "some error message")));

            AgentToolExecutionResult result = executor.execute("fail_tool", Map.of(), ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo("ERR");
            assertThat(result.errorMessage()).isEqualTo("some error message");
        }
    }

    @Nested
    @DisplayName("result sanitization")
    class ResultSanitizationTests {

        @Test
        @DisplayName("sanitizes successful tool result content")
        void sanitizesSuccessResult() {
            registry.register(createSimpleTool("verbose_tool", ctxRef ->
                    AgentToolExecutionResult.success("clean result")));

            AgentToolExecutionResult result = executor.execute("verbose_tool", Map.of(), ctx);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("clean result");
        }

        @Test
        @DisplayName("truncates oversized result")
        void truncatesOversizedResult() {
            AgentProperties narrowProps = new AgentProperties();
            narrowProps.setToolTimeoutSeconds(5);
            narrowProps.setToolMaxResultChars(50);
            narrowProps.setToolCorePoolSize(2);
            narrowProps.setToolMaxPoolSize(4);
            narrowProps.setToolQueueCapacity(8);
            ToolResultSanitizer narrowSanitizer = new ToolResultSanitizer(narrowProps);
            ToolExecutorImpl narrowExecutor = new ToolExecutorImpl(registry, objectMapper, validator, narrowSanitizer, narrowProps, taskExecutor);

            ToolRegistryImpl localRegistry = new ToolRegistryImpl();
            String longContent = "a".repeat(200);
            localRegistry.register(createSimpleTool("big_tool", ctxRef -> AgentToolExecutionResult.success(longContent)));
            narrowExecutor = new ToolExecutorImpl(localRegistry, objectMapper, validator, narrowSanitizer, narrowProps, taskExecutor);

            AgentToolExecutionResult result = narrowExecutor.execute("big_tool", Map.of(), ctx);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).endsWith("(truncated)");
            assertThat(result.content().length()).isLessThanOrEqualTo(50);
        }
    }

    @Nested
    @DisplayName("previous failure does not poison next execution")
    class NoPoisonTests {

        @Test
        @DisplayName("executor handles subsequent execution after previous failure")
        void subsequentExecutionAfterFailure() {
            registry.register(createSimpleTool("fail_tool", ctxRef -> {
                throw new RuntimeException("fail");
            }));
            executor.execute("fail_tool", Map.of(), ctx);

            registry.register(createSimpleTool("ok_tool", ctxRef -> AgentToolExecutionResult.success("recovered")));
            AgentToolExecutionResult result = executor.execute("ok_tool", Map.of(), ctx);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("recovered");
        }
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private static AgentTool<Object> createSimpleTool(String name,
                                                       java.util.function.Function<ToolExecutionContext, AgentToolExecutionResult> fn) {
        return new AgentTool<>() {
            @Override public String name() { return name; }
            @Override public String description() { return "test"; }
            @Override public String parametersSchema() { return "{\"type\":\"object\"}"; }
            @Override public Class<Object> argumentType() { return Object.class; }
            @Override public AgentToolExecutionResult execute(ToolExecutionContext ctx, Object args) {
                return fn.apply(ctx);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private <T> void registerDtoTool(String name, Class<T> argType,
                                      java.util.function.BiFunction<ToolExecutionContext, T, AgentToolExecutionResult> fn) {
        registry.register(new AgentTool<T>() {
            @Override public String name() { return name; }
            @Override public String description() { return "test"; }
            @Override public String parametersSchema() { return "{\"type\":\"object\"}"; }
            @Override public Class<T> argumentType() { return argType; }
            @Override public AgentToolExecutionResult execute(ToolExecutionContext ctx, T args) {
                return fn.apply(ctx, args);
            }
        });
    }

    public static class ValidatedDto {
        @NotBlank
        @Size(min = 3, max = 100)
        private String name;
        @Min(1)
        private int count;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
    }
}