package com.intellidesk.evaluation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for evaluation dataset validation: schema rules, zero-relevant
 * exclusion, stable chunk identity (no DB ids), duplicate ids.
 */
class EvalDatasetValidatorTest {

    private static Map<String, Object> q(String id, boolean qualified, List<String> chunks,
                                         List<String> docs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("question", "question for " + id);
        m.put("qualified_quality_question", qualified);
        m.put("relevant_chunks", chunks);
        m.put("relevant_documents", docs);
        m.put("reference_answer", "answer");
        m.put("relevance_notes", "desc");
        return m;
    }

    @Test
    void validQualityQuestionPasses() {
        Map<String, Object> ok = q("qq-1", true, List.of("doc-a|0", "doc-a|1"), List.of("doc-a"));
        EvalDatasetValidator.Validation v = EvalDatasetValidator.validate(List.of(ok));
        assertTrue(v.valid());
        assertEquals(1, v.qualityCount());
        assertEquals(0, v.adversarialCount());
    }

    @Test
    void validAdversarialQuestionPasses() {
        Map<String, Object> adv = q("adv-1", false, List.of(), List.of());
        EvalDatasetValidator.Validation v = EvalDatasetValidator.validate(List.of(adv));
        assertTrue(v.valid());
        assertEquals(0, v.qualityCount());
        assertEquals(1, v.adversarialCount());
    }

    @Test
    void qualityQuestionWithoutChunkIsRejected() {
        Map<String, Object> bad = q("qq-x", true, List.of(), List.of("doc-a"));
        EvalDatasetValidator.Validation v = EvalDatasetValidator.validate(List.of(bad));
        assertFalse(v.valid());
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("must have >=1 relevant_chunk")));
    }

    @Test
    void qualityQuestionWithoutDocumentIsRejected() {
        Map<String, Object> bad = q("qq-y", true, List.of("doc-a|0"), List.of());
        EvalDatasetValidator.Validation v = EvalDatasetValidator.validate(List.of(bad));
        assertFalse(v.valid());
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("must have >=1 relevant document")));
    }

    @Test
    void adversarialWithChunkRejected() {
        Map<String, Object> bad = q("adv-x", false, List.of("doc-a|0"), List.of());
        EvalDatasetValidator.Validation v = EvalDatasetValidator.validate(List.of(bad));
        assertFalse(v.valid());
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("must have 0 relevant_chunks")));
    }

    @Test
    void unstableDbChunkIdRejected() {
        Map<String, Object> bad = q("qq-z", true, List.of("42"), List.of("doc-a"));
        EvalDatasetValidator.Validation v = EvalDatasetValidator.validate(List.of(bad));
        assertFalse(v.valid());
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("unstable/DB id")));
    }

    @Test
    void duplicateIdRejected() {
        Map<String, Object> a = q("dup", true, List.of("doc-a|0"), List.of("doc-a"));
        Map<String, Object> b = q("dup", false, List.of(), List.of());
        EvalDatasetValidator.Validation v = EvalDatasetValidator.validate(List.of(a, b));
        assertFalse(v.valid());
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("duplicate question id")));
    }

    @Test
    void missingIdOrQuestionRejected() {
        Map<String, Object> noQuestion = new LinkedHashMap<>();
        noQuestion.put("id", "x");
        noQuestion.put("qualified_quality_question", true);
        noQuestion.put("relevant_chunks", List.of("doc-a|0"));
        noQuestion.put("relevant_documents", List.of("doc-a"));
        EvalDatasetValidator.Validation v = EvalDatasetValidator.validate(List.of(noQuestion));
        assertFalse(v.valid());
        assertTrue(v.errors().stream().anyMatch(e -> e.contains("missing id or question")));
    }

    @Test
    void invalidBooleanRejected() {
        Map<String, Object> m = q("qq-bad", true, List.of("doc-a|0"), List.of("doc-a"));
        m.put("qualified_quality_question", "notabool");
        EvalDatasetValidator.Validation v = EvalDatasetValidator.validate(List.of(m));
        assertFalse(v.valid());
    }

    @Test
    void stableChunkIdentityParsesToDocAndOrdinal() {
        String sep = EvalDatasetValidator.STABLE_CHUNK_ID_SEPARATOR;
        String logical = "doc-a" + sep + "3";
        // identity must be deterministic and separable into doc id + ordinal
        int bar = logical.indexOf(sep);
        assertEquals("doc-a", logical.substring(0, bar));
        assertEquals("3", logical.substring(bar + 1));
        // equality is stable across reconstruction
        assertEquals(logical, "doc-" + "a" + sep + 3);
    }

    @Test
    void qualityQuestionWithStableMultiChunkPassesValidator() {
        Map<String, Object> ok = q("qq-multi", true,
                List.of("doc-a|0", "doc-b|0", "doc-a|1"), List.of("doc-a", "doc-b"));
        EvalDatasetValidator.Validation v = EvalDatasetValidator.validate(List.of(ok));
        assertTrue(v.valid());
        EvalDatasetValidator.Question q = v.questions().get(0);
        assertEquals(3, q.relevantChunks().size());
        assertEquals(2, q.relevantDocuments().size());
    }

    @Test
    void byIdIndexesStably() {
        Map<String, Object> a = q("qa", true, List.of("doc-a|0"), List.of("doc-a"));
        Map<String, Object> b = q("qb", false, List.of(), List.of());
        EvalDatasetValidator.Validation v = EvalDatasetValidator.validate(List.of(a, b));
        assertEquals(2, v.questions().size());
        Map<String, EvalDatasetValidator.Question> idx = EvalDatasetValidator.byId(v.questions());
        assertEquals(2, idx.size());
        assertEquals("qa", idx.get("qa").id());
    }
}