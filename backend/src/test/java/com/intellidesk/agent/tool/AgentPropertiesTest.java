package com.intellidesk.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgentProperties")
class AgentPropertiesTest {

    @Nested
    @DisplayName("defaults")
    class DefaultsTests {

        @Test
        @DisplayName("has correct default values")
        void hasCorrectDefaults() {
            AgentProperties props = new AgentProperties();

            assertThat(props.getToolTimeoutSeconds()).isEqualTo(30);
            assertThat(props.getToolMaxResultChars()).isEqualTo(16384);
            assertThat(props.getToolCorePoolSize()).isEqualTo(2);
            assertThat(props.getToolMaxPoolSize()).isEqualTo(4);
            assertThat(props.getToolQueueCapacity()).isEqualTo(16);
        }

        @Test
        @DisplayName("default values pass validation")
        void defaultValuesPassValidation() {
            AgentProperties props = new AgentProperties();
            props.validate(); // should not throw
        }
    }

    @Nested
    @DisplayName("validation")
    class ValidationTests {

        @Test
        @DisplayName("rejects toolTimeoutSeconds < 1")
        void rejectsTimeoutTooLow() {
            AgentProperties props = new AgentProperties();
            props.setToolTimeoutSeconds(0);
            assertThatThrownBy(props::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tool-timeout-seconds");
        }

        @Test
        @DisplayName("rejects toolTimeoutSeconds > 300")
        void rejectsTimeoutTooHigh() {
            AgentProperties props = new AgentProperties();
            props.setToolTimeoutSeconds(301);
            assertThatThrownBy(props::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tool-timeout-seconds");
        }

        @Test
        @DisplayName("rejects toolMaxResultChars < 256")
        void rejectsMaxCharsTooLow() {
            AgentProperties props = new AgentProperties();
            props.setToolMaxResultChars(255);
            assertThatThrownBy(props::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tool-max-result-chars");
        }

        @Test
        @DisplayName("rejects toolMaxResultChars > 65536")
        void rejectsMaxCharsTooHigh() {
            AgentProperties props = new AgentProperties();
            props.setToolMaxResultChars(65537);
            assertThatThrownBy(props::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tool-max-result-chars");
        }

        @Test
        @DisplayName("rejects toolCorePoolSize < 1")
        void rejectsCorePoolTooLow() {
            AgentProperties props = new AgentProperties();
            props.setToolCorePoolSize(0);
            assertThatThrownBy(props::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tool-core-pool-size");
        }

        @Test
        @DisplayName("rejects toolCorePoolSize > 16")
        void rejectsCorePoolTooHigh() {
            AgentProperties props = new AgentProperties();
            props.setToolCorePoolSize(17);
            assertThatThrownBy(props::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tool-core-pool-size");
        }

        @Test
        @DisplayName("rejects toolMaxPoolSize < toolCorePoolSize")
        void rejectsMaxPoolBelowCore() {
            AgentProperties props = new AgentProperties();
            props.setToolCorePoolSize(4);
            props.setToolMaxPoolSize(3);
            assertThatThrownBy(props::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tool-max-pool-size");
        }

        @Test
        @DisplayName("rejects toolMaxPoolSize > 32")
        void rejectsMaxPoolTooHigh() {
            AgentProperties props = new AgentProperties();
            props.setToolMaxPoolSize(33);
            assertThatThrownBy(props::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tool-max-pool-size");
        }

        @Test
        @DisplayName("rejects toolQueueCapacity < 4")
        void rejectsQueueTooLow() {
            AgentProperties props = new AgentProperties();
            props.setToolQueueCapacity(3);
            assertThatThrownBy(props::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tool-queue-capacity");
        }

        @Test
        @DisplayName("rejects toolQueueCapacity > 256")
        void rejectsQueueTooHigh() {
            AgentProperties props = new AgentProperties();
            props.setToolQueueCapacity(257);
            assertThatThrownBy(props::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tool-queue-capacity");
        }
    }

    @Nested
    @DisplayName("executor config")
    class ExecutorConfigTests {

        @Test
        @DisplayName("executor bean is created with configured values")
        void executorBeanCreated() {
            AgentProperties props = new AgentProperties();
            props.setToolCorePoolSize(3);
            props.setToolMaxPoolSize(6);
            props.setToolQueueCapacity(16);

            AgentToolExecutorConfig config = new AgentToolExecutorConfig();
            ThreadPoolTaskExecutor executor = config.agentToolTaskExecutor(props);

            assertThat(executor.getCorePoolSize()).isEqualTo(3);
            assertThat(executor.getMaxPoolSize()).isEqualTo(6);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("agent-tool-");
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(16);

            executor.shutdown();
        }

        @Test
        @DisplayName("executor uses AbortPolicy for rejection")
        void executorUsesAbortPolicy() {
            AgentProperties props = new AgentProperties();
            AgentToolExecutorConfig config = new AgentToolExecutorConfig();
            ThreadPoolTaskExecutor executor = config.agentToolTaskExecutor(props);

            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.AbortPolicy.class);

            executor.shutdown();
        }
    }
}