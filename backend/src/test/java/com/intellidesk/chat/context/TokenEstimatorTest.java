package com.intellidesk.chat.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenEstimator")
class TokenEstimatorTest {

    @Test
    @DisplayName("null text returns 0")
    void nullText() {
        assertThat(TokenEstimator.estimate(null)).isZero();
    }

    @Test
    @DisplayName("empty text returns 0")
    void emptyText() {
        assertThat(TokenEstimator.estimate("")).isZero();
    }

    @Test
    @DisplayName("ASCII text")
    void asciiText() {
        // "Hello World" = 11 chars, ~3 tokens
        int tokens = TokenEstimator.estimate("Hello World");
        assertThat(tokens).isEqualTo(3);
    }

    @Test
    @DisplayName("CJK text")
    void cjkText() {
        // 8 Chinese chars, 8/1.5 = 5.33, ceil = 6
        int tokens = TokenEstimator.estimate("你好世界测试文本");
        assertThat(tokens).isEqualTo(6);
    }

    @Test
    @DisplayName("mixed text")
    void mixedText() {
        int tokens = TokenEstimator.estimate("Hello 你好 World 世界");
        assertThat(tokens).isGreaterThan(0);
    }

    @Test
    @DisplayName("monotonic: longer text has more tokens")
    void monotonic() {
        int shortTokens = TokenEstimator.estimate("ab");
        int longTokens = TokenEstimator.estimate("abcdefghij");
        assertThat(longTokens).isGreaterThanOrEqualTo(shortTokens);
    }

    @Test
    @DisplayName("deterministic: same input same output")
    void deterministic() {
        String text = "Hello World 你好世界";
        int t1 = TokenEstimator.estimate(text);
        int t2 = TokenEstimator.estimate(text);
        assertThat(t1).isEqualTo(t2);
    }
}