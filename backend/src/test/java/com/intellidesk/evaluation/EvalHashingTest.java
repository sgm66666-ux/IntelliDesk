package com.intellidesk.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for canonical hashing used in corpus/dataset/config freeze:
 * determinism, key-order insensitivity, byte-change sensitivity, chain hash.
 */
class EvalHashingTest {

    @Test
    void sha256OfEmptyStringMatchesKnownVector() {
        // SHA-256 of empty input
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                EvalHashing.sha256Hex(""));
    }

    @Test
    void sha256HexDeterministic() {
        String a = EvalHashing.sha256Hex("hello");
        String b = EvalHashing.sha256Hex((byte[]) "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(a, b);
        assertEquals(64, a.length());
    }

    @Test
    void canonicalJsonIsKeyOrderInsensitive() {
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("a", 1);
        m1.put("b", "x");
        m1.put("c", java.util.List.of(1, 2));
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("c", java.util.List.of(1, 2));
        m2.put("b", "x");
        m2.put("a", 1);
        assertEquals(EvalHashing.canonicalJson(m1), EvalHashing.canonicalJson(m2));
        assertEquals(EvalHashing.sha256Hex(EvalHashing.canonicalJson(m1)),
                EvalHashing.sha256Hex(EvalHashing.canonicalJson(m2)));
    }

    @Test
    void canonicalJsonHandlesUnicodeAndEscapes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "中文");
        String json = EvalHashing.canonicalJson(m);
        assertTrue(json.contains("中文")); // non-ASCII kept as literal chars, deterministic
        // quote and backslash are escaped
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("k", "a\"b\\c");
        String json2 = EvalHashing.canonicalJson(m2);
        assertTrue(json2.contains("a\\\"b\\\\c"));
        // control char escaped as backslash-u + hex
        Map<String, Object> m3 = new LinkedHashMap<>();
        m3.put("k", "a\nb");
        String json3 = EvalHashing.canonicalJson(m3);
        assertTrue(json3.contains("a\\nb"));
    }

    @Test
    void byteChangeChangesDigest() {
        byte[] a = "the quick brown fox".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = "the quick brown fuox".getBytes(java.nio.charset.StandardCharsets.UTF_8); // mutated byte
        assertNotEquals(EvalHashing.sha256Hex(a), EvalHashing.sha256Hex(b));
    }

    @Test
    void chainCorpusHashBindsAllThreeComponents() {
        String base = EvalHashing.chainCorpusHash("s1", "p1", "i1");
        assertNotEquals(base, EvalHashing.chainCorpusHash("s2", "p1", "i1")); // source change
        assertNotEquals(base, EvalHashing.chainCorpusHash("s1", "p2", "i1")); // parser config change
        assertNotEquals(base, EvalHashing.chainCorpusHash("s1", "p1", "i2")); // indexed manifest change
        assertEquals(base, EvalHashing.chainCorpusHash("s1", "p1", "i1")); // stable
    }

    @Test
    void sourceByteChangeFlowsToCorpusHash() {
        // simulate freeze chain: source_content_hash -> corpus_hash
        String s1 = EvalHashing.sha256Hex("original source bytes");
        String s2 = EvalHashing.sha256Hex("original source byte!"); // one byte differs
        String corpus1 = EvalHashing.chainCorpusHash(s1, "p", "i");
        String corpus2 = EvalHashing.chainCorpusHash(s2, "p", "i");
        assertNotEquals(s1, s2);
        assertNotEquals(corpus1, corpus2);
    }

    @Test
    void retrievalConfigHashMatchesFrozenArtifact() throws Exception {
        // ONE authoritative canonical serializer + ONE hash must produce the same
        // retrieval_config_hash that is frozen in docs/evaluation/config/retrieval_config.json.
        String root = EvalManifestGenerator.resolveProjectRoot();
        ObjectMapper om = new ObjectMapper();
        JsonNode cfg = om.readTree(Files.readString(Path.of(root, "docs/evaluation/config/retrieval_config.json")));

        Map<String, Object> embedding = Map.of(
                "provider_mode", cfg.get("embedding").get("provider_mode").asText(),
                "model", cfg.get("embedding").get("model").asText(),
                "dimension", cfg.get("embedding").get("dimension").asInt());

        Map<String, Object> retrievalParams = new LinkedHashMap<>();
        JsonNode params = cfg.get("retrieval_params");
        retrievalParams.put("candidate_top_k", params.get("candidate_top_k").asInt());
        retrievalParams.put("top_k", params.get("top_k").asInt());
        List<Integer> reportedK = new java.util.ArrayList<>();
        params.get("reported_k").forEach(n -> reportedK.add(n.asInt()));
        retrievalParams.put("reported_k", reportedK);
        retrievalParams.put("rrf_k", params.get("rrf_k").asInt());
        retrievalParams.put("es_index", params.get("es_index").asText());
        retrievalParams.put("index_generation", params.get("index_generation").asInt());

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("embedding", embedding);
        block.put("reranker_quality_config_hash", cfg.get("reranker_quality_config_hash").asText());
        block.put("retrieval_params", retrievalParams);

        String computed = EvalHashing.sha256Hex(EvalHashing.canonicalJson(block));
        String frozen = cfg.get("retrieval_config_hash").asText();
        assertEquals(frozen, computed,
                "Java canonical serializer/hash must match frozen retrieval_config_hash");
    }

    @Test
    void retrievalConfigHashIsOrderInsensitive() {
        Map<String, Object> embedding = Map.of(
                "provider_mode", "LOCAL_OLLAMA_REAL",
                "model", "qwen3-embedding:8b",
                "dimension", 1536);
        Map<String, Object> params = Map.of(
                "candidate_top_k", 50,
                "top_k", 10,
                "reported_k", List.of(1, 3, 5, 10),
                "rrf_k", 60,
                "es_index", "intellidesk-chunks-v1",
                "index_generation", 1);
        Map<String, Object> block1 = new LinkedHashMap<>();
        block1.put("embedding", embedding);
        block1.put("reranker_quality_config_hash", "abc");
        block1.put("retrieval_params", params);

        Map<String, Object> block2 = new LinkedHashMap<>();
        block2.put("retrieval_params", params);
        block2.put("embedding", embedding);
        block2.put("reranker_quality_config_hash", "abc");

        assertEquals(
                EvalHashing.sha256Hex(EvalHashing.canonicalJson(block1)),
                EvalHashing.sha256Hex(EvalHashing.canonicalJson(block2)));
    }

    @Test
    void retrievalConfigHashChangesOnSemanticChange() {
        Map<String, Object> baseEmbedding = new LinkedHashMap<>();
        baseEmbedding.put("provider_mode", "LOCAL_OLLAMA_REAL");
        baseEmbedding.put("model", "qwen3-embedding:8b");
        baseEmbedding.put("dimension", 1536);
        Map<String, Object> params = Map.of(
                "candidate_top_k", 50,
                "top_k", 10,
                "reported_k", List.of(1, 3, 5, 10),
                "rrf_k", 60,
                "es_index", "intellidesk-chunks-v1",
                "index_generation", 1);
        Map<String, Object> base = Map.of(
                "embedding", baseEmbedding,
                "reranker_quality_config_hash", "abc",
                "retrieval_params", params);
        String baseHash = EvalHashing.sha256Hex(EvalHashing.canonicalJson(base));

        Map<String, Object> changedEmbedding = new LinkedHashMap<>(baseEmbedding);
        changedEmbedding.put("dimension", 768);
        Map<String, Object> changed = Map.of(
                "embedding", changedEmbedding,
                "reranker_quality_config_hash", "abc",
                "retrieval_params", params);
        String changedHash = EvalHashing.sha256Hex(EvalHashing.canonicalJson(changed));
        assertNotEquals(baseHash, changedHash);
    }
}