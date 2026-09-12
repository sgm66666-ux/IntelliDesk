package com.intellidesk.chat.context;

/**
 * Deterministic token count approximation.
 * Uses character-based estimation: CJK ~1.5 chars/token, ASCII ~4 chars/token.
 * This is NOT a real tokenizer — it provides a monotonic, deterministic approximation.
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    /**
     * Estimate token count for a given text.
     * - CJK characters (Unicode blocks): ~1.5 chars per token
     * - ASCII/other: ~4 chars per token
     * - Weighted average based on actual character mix
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int cjkChars = 0;
        int otherChars = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjkChars++;
            } else {
                otherChars++;
            }
        }

        if (cjkChars + otherChars == 0) {
            return 0;
        }

        double estimated = (cjkChars / 1.5) + (otherChars / 4.0);
        return (int) Math.ceil(estimated);
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_G
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_H
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_A
                || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_B
                || block == Character.UnicodeBlock.BOPOMOFO
                || block == Character.UnicodeBlock.BOPOMOFO_EXTENDED;
    }
}