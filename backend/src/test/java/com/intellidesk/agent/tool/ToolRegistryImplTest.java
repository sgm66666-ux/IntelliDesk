package com.intellidesk.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ToolRegistryImpl")
class ToolRegistryImplTest {

    private final ToolRegistryImpl registry = new ToolRegistryImpl();

    @SuppressWarnings("unchecked")
    private static AgentTool<Object> createMockTool(String name) {
        return createMockTool(name, "{\"type\":\"object\"}", Object.class);
    }

    @SuppressWarnings("unchecked")
    private static AgentTool<Object> createMockTool(String name, String schema, Class<?> argType) {
        return new AgentTool<>() {
            @Override
            public String name() { return name; }
            @Override
            public String description() { return "test tool " + name; }
            @Override
            public String parametersSchema() { return schema; }
            @Override
            public Class<Object> argumentType() { return (Class<Object>) argType; }
            @Override
            public AgentToolExecutionResult execute(ToolExecutionContext ctx, Object arguments) {
                return AgentToolExecutionResult.success("ok");
            }
        };
    }

    @Nested
    @DisplayName("register")
    class RegisterTests {

        @Test
        @DisplayName("registers a tool and retrieves it by name")
        void registersTool() {
            AgentTool<?> tool = createMockTool("test_tool");
            registry.register(tool);
            assertThat(registry.get("test_tool")).isSameAs(tool);
        }

        @Test
        @DisplayName("registers multiple tools")
        void registersMultipleTools() {
            registry.register(createMockTool("tool_a"));
            registry.register(createMockTool("tool_b"));
            assertThat(registry.registeredNames()).containsExactlyInAnyOrder("tool_a", "tool_b");
        }

        @Test
        @DisplayName("throws on duplicate name")
        void throwsOnDuplicateName() {
            registry.register(createMockTool("dup_tool"));
            assertThatThrownBy(() -> registry.register(createMockTool("dup_tool")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate tool name")
                    .hasMessageContaining("dup_tool");
        }

        @Test
        @DisplayName("throws on null tool")
        void throwsOnNullTool() {
            assertThatThrownBy(() -> registry.register(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Tool must not be null");
        }

        @Test
        @DisplayName("throws on null name")
        void throwsOnNullName() {
            AgentTool<?> tool = createMockTool(null);
            assertThatThrownBy(() -> registry.register(tool))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Tool name must not be blank");
        }

        @Test
        @DisplayName("throws on blank name")
        void throwsOnBlankName() {
            AgentTool<?> tool = createMockTool("   ");
            assertThatThrownBy(() -> registry.register(tool))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Tool name must not be blank");
        }

        @Test
        @DisplayName("throws on null argumentType")
        void throwsOnNullArgumentType() {
            AgentTool<?> tool = new AgentTool<>() {
                @Override public String name() { return "bad_tool"; }
                @Override public String description() { return "bad"; }
                @Override public String parametersSchema() { return "{\"type\":\"object\"}"; }
                @Override public Class<Object> argumentType() { return null; }
                @Override public AgentToolExecutionResult execute(ToolExecutionContext ctx, Object args) {
                    return AgentToolExecutionResult.success("ok");
                }
            };
            assertThatThrownBy(() -> registry.register(tool))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("argumentType must not be null");
        }

        @Test
        @DisplayName("throws on null parametersSchema")
        void throwsOnNullParametersSchema() {
            AgentTool<?> tool = createMockTool("bad", null, Object.class);
            assertThatThrownBy(() -> registry.register(tool))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("parametersSchema must not be blank");
        }

        @Test
        @DisplayName("throws on blank parametersSchema")
        void throwsOnBlankParametersSchema() {
            AgentTool<?> tool = createMockTool("bad", "  ", Object.class);
            assertThatThrownBy(() -> registry.register(tool))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("parametersSchema must not be blank");
        }

        @Test
        @DisplayName("throws on malformed JSON parametersSchema")
        void throwsOnMalformedJsonSchema() {
            AgentTool<?> tool = createMockTool("bad", "not-json", Object.class);
            assertThatThrownBy(() -> registry.register(tool))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("parametersSchema is not valid JSON");
        }
    }

    @Nested
    @DisplayName("get")
    class GetTests {

        @Test
        @DisplayName("returns null for unknown tool name")
        void returnsNullForUnknown() {
            assertThat(registry.get("nonexistent")).isNull();
        }

        @Test
        @DisplayName("returns registered tool")
        void returnsRegisteredTool() {
            AgentTool<?> tool = createMockTool("my_tool");
            registry.register(tool);
            assertThat(registry.get("my_tool")).isSameAs(tool);
        }
    }

    @Nested
    @DisplayName("listDescriptors")
    class ListDescriptorsTests {

        @Test
        @DisplayName("returns empty list when no tools registered")
        void returnsEmptyList() {
            assertThat(registry.listDescriptors()).isEmpty();
        }

        @Test
        @DisplayName("returns descriptors for all registered tools")
        void returnsAllDescriptors() {
            registry.register(createMockTool("tool_a"));
            registry.register(createMockTool("tool_b"));
            assertThat(registry.listDescriptors()).hasSize(2);
            assertThat(registry.listDescriptors().stream().map(AgentToolDescriptor::name))
                    .containsExactlyInAnyOrder("tool_a", "tool_b");
        }

        @Test
        @DisplayName("returned list is immutable")
        void returnedListIsImmutable() {
            registry.register(createMockTool("tool_a"));
            assertThatThrownBy(() -> registry.listDescriptors().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("registeredNames")
    class RegisteredNamesTests {

        @Test
        @DisplayName("returns empty set when no tools registered")
        void returnsEmptySet() {
            assertThat(registry.registeredNames()).isEmpty();
        }

        @Test
        @DisplayName("returns unmodifiable set")
        void returnsUnmodifiableSet() {
            registry.register(createMockTool("tool_a"));
            assertThatThrownBy(() -> registry.registeredNames().add("hack"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}