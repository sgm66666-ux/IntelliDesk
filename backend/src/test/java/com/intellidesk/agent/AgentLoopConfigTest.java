package com.intellidesk.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgentLoopConfig")
class AgentLoopConfigTest {

    @Nested
    @DisplayName("default values")
    class DefaultValuesTests {

        @Test
        @DisplayName("default maxSteps is 10")
        void defaultMaxSteps() {
            AgentLoopConfig config = new AgentLoopConfig();
            assertThat(config.getMaxSteps()).isEqualTo(10);
        }

        @Test
        @DisplayName("default timeoutSeconds is 120")
        void defaultTimeoutSeconds() {
            AgentLoopConfig config = new AgentLoopConfig();
            assertThat(config.getTimeoutSeconds()).isEqualTo(120);
        }

        @Test
        @DisplayName("default systemPrompt is not blank")
        void defaultSystemPrompt() {
            AgentLoopConfig config = new AgentLoopConfig();
            assertThat(config.getSystemPrompt()).isNotBlank();
        }

        @Test
        @DisplayName("default values pass validation")
        void defaultValuesPassValidation() {
            AgentLoopConfig config = new AgentLoopConfig();
            assertThatCode(config::validate).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("maxSteps validation")
    class MaxStepsValidationTests {

        @Test
        @DisplayName("maxSteps = 0 throws")
        void maxStepsZeroThrows() {
            AgentLoopConfig config = new AgentLoopConfig();
            config.setMaxSteps(0);
            assertThatThrownBy(config::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("max-steps");
        }

        @Test
        @DisplayName("maxSteps = -1 throws")
        void maxStepsNegativeThrows() {
            AgentLoopConfig config = new AgentLoopConfig();
            config.setMaxSteps(-1);
            assertThatThrownBy(config::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("max-steps");
        }

        @Test
        @DisplayName("maxSteps = 51 throws")
        void maxStepsExceedsUpperBoundThrows() {
            AgentLoopConfig config = new AgentLoopConfig();
            config.setMaxSteps(51);
            assertThatThrownBy(config::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("max-steps");
        }

        @Test
        @DisplayName("maxSteps = 1 passes")
        void maxStepsMinValid() {
            AgentLoopConfig config = new AgentLoopConfig();
            config.setMaxSteps(1);
            assertThatCode(config::validate).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("maxSteps = 50 passes")
        void maxStepsMaxValid() {
            AgentLoopConfig config = new AgentLoopConfig();
            config.setMaxSteps(50);
            assertThatCode(config::validate).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("timeoutSeconds validation")
    class TimeoutSecondsValidationTests {

        @Test
        @DisplayName("timeoutSeconds = 5 throws")
        void timeoutSecondsTooSmallThrows() {
            AgentLoopConfig config = new AgentLoopConfig();
            config.setTimeoutSeconds(5);
            assertThatThrownBy(config::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("timeout-seconds");
        }

        @Test
        @DisplayName("timeoutSeconds = 601 throws")
        void timeoutSecondsExceedsUpperBoundThrows() {
            AgentLoopConfig config = new AgentLoopConfig();
            config.setTimeoutSeconds(601);
            assertThatThrownBy(config::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("timeout-seconds");
        }

        @Test
        @DisplayName("timeoutSeconds = 10 passes")
        void timeoutSecondsMinValid() {
            AgentLoopConfig config = new AgentLoopConfig();
            config.setTimeoutSeconds(10);
            assertThatCode(config::validate).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("timeoutSeconds = 600 passes")
        void timeoutSecondsMaxValid() {
            AgentLoopConfig config = new AgentLoopConfig();
            config.setTimeoutSeconds(600);
            assertThatCode(config::validate).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("setter/getter")
    class SetterGetterTests {

        @Test
        @DisplayName("can set and get maxSteps")
        void setAndGetMaxSteps() {
            AgentLoopConfig config = new AgentLoopConfig();
            config.setMaxSteps(5);
            assertThat(config.getMaxSteps()).isEqualTo(5);
        }

        @Test
        @DisplayName("can set and get timeoutSeconds")
        void setAndGetTimeoutSeconds() {
            AgentLoopConfig config = new AgentLoopConfig();
            config.setTimeoutSeconds(60);
            assertThat(config.getTimeoutSeconds()).isEqualTo(60);
        }

        @Test
        @DisplayName("can set and get systemPrompt")
        void setAndGetSystemPrompt() {
            AgentLoopConfig config = new AgentLoopConfig();
            config.setSystemPrompt("Custom prompt");
            assertThat(config.getSystemPrompt()).isEqualTo("Custom prompt");
        }
    }
}