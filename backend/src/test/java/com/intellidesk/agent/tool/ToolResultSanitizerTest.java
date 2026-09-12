package com.intellidesk.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolResultSanitizer")
class ToolResultSanitizerTest {

    private ToolResultSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        AgentProperties props = new AgentProperties();
        props.setToolMaxResultChars(100);
        sanitizer = new ToolResultSanitizer(props);
    }

    @Nested
    @DisplayName("normal content")
    class NormalContentTests {

        @Test
        @DisplayName("returns content unchanged when within limit")
        void returnsContentUnchanged() {
            String result = sanitizer.sanitize("short content");
            assertThat(result).isEqualTo("short content");
        }

        @Test
        @DisplayName("returns empty string for null input")
        void returnsEmptyForNull() {
            assertThat(sanitizer.sanitize(null)).isEqualTo("");
        }
    }

    @Nested
    @DisplayName("truncation")
    class TruncationTests {

        @Test
        @DisplayName("truncates long content and adds truncated suffix")
        void truncatesLongContent() {
            String longContent = "a".repeat(200);
            String result = sanitizer.sanitize(longContent);

            assertThat(result).endsWith("(truncated)");
            assertThat(result.length()).isLessThanOrEqualTo(100);
        }
    }

    @Nested
    @DisplayName("sensitive info cleaning")
    class SensitiveInfoCleaningTests {

        @Test
        @DisplayName("removes stack trace lines")
        void removesStackTraceLines() {
            String withStack = "Error occurred\n\tat com.intellidesk.SomeClass.method(SomeClass.java:42)\n\tat com.intellidesk.OtherClass.call(OtherClass.java:10)";
            String result = sanitizer.sanitize(withStack);

            assertThat(result).doesNotContain("SomeClass.java");
            assertThat(result).doesNotContain("OtherClass.java");
        }

        @Test
        @DisplayName("removes SQL exception details")
        void removesSqlExceptionDetails() {
            String withSql = "SQLException: column \"foo\" does not exist\nPosition: 42";
            String result = sanitizer.sanitize(withSql);

            assertThat(result).doesNotContain("column \"foo\" does not exist");
            assertThat(result).contains("[internal error]");
        }

        @Test
        @DisplayName("removes Caused by lines")
        void removesCausedByLines() {
            String withCausedBy = "Caused by: java.lang.NullPointerException at line 5";
            String result = sanitizer.sanitize(withCausedBy);

            assertThat(result).doesNotContain("Caused by");
        }

        @Test
        @DisplayName("removes Windows path")
        void removesWindowsPath() {
            String withPath = "Error reading C:\\Users\\admin\\Documents\\secret.txt";
            String result = sanitizer.sanitize(withPath);

            assertThat(result).doesNotContain("secret.txt");
        }

        @Test
        @DisplayName("removes Linux path")
        void removesLinuxPath() {
            String withPath = "Error reading /home/admin/secret.txt";
            String result = sanitizer.sanitize(withPath);

            assertThat(result).doesNotContain("secret.txt");
        }
    }
}