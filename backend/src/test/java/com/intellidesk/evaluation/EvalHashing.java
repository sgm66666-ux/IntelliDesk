package com.intellidesk.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Deterministic/canonical hashing helpers for Phase 8 Wave 1 RAG evaluation.
 *
 * All serialization here is canonical: UTF-8, stable map field order, arrays sorted
 * where required, no pretty-printing. Identical logical input always yields identical
 * hash, independent of insertion order or platform line endings.
 */
public final class EvalHashing {

    private EvalHashing() {
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String s) {
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Canonical JSON of a Map. Keys sorted lexicographically; values serialized
     * deterministically with compact separators and escaped (non-ASCII) output.
     *
     * This is the ONE authoritative canonical serializer for Phase 8 evaluation config
     * artifacts. Identical logical input yields identical bytes regardless of insertion
     * order or platform line endings.
     */
    public static String canonicalJson(Map<String, ?> map) {
        StringBuilder sb = new StringBuilder();
        writeObject(map, sb);
        return sb.toString();
    }

    /**
     * Canonical JSON of any value. Supports nested Map/List/Number/String/Boolean/null.
     * Map keys are sorted lexicographically; arrays preserve their order.
     */
    public static String canonicalJson(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    /** Concatenation-based corpus hash per MUST_FIX-1 freeze model. */
    public static String chainCorpusHash(String sourceContentHash, String parserChunkConfigHash,
                                         String indexedChunkManifestHash) {
        return sha256Hex(sourceContentHash + parserChunkConfigHash + indexedChunkManifestHash);
    }

    // ---- canonical writer -------------------------------------------------

    private static void writeObject(Map<?, ?> map, StringBuilder sb) {
        List<?> keys = map.keySet().stream().sorted(Comparator.comparing(Object::toString)).toList();
        sb.append('{');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeString(String.valueOf(keys.get(i)), sb);
            sb.append(':');
            writeValue(map.get(keys.get(i)), sb);
        }
        sb.append('}');
    }

    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> m) {
            writeObject(m, sb);
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeValue(list.get(i), sb);
            }
            sb.append(']');
        } else if (value instanceof String s) {
            writeString(s, sb);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Number n) {
            // Normalize Number output so Float(1.0f) and Double(1.0) look identical when possible.
            double d = n.doubleValue();
            if (Double.isNaN(d)) {
                sb.append("null");
            } else if (Double.isInfinite(d)) {
                sb.append(d > 0 ? "null" : "null");
            } else if (d == Math.rint(d) && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                sb.append((long) d);
            } else {
                sb.append(n.toString());
            }
        } else {
            // default: escape as string via toString
            writeString(String.valueOf(value), sb);
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}