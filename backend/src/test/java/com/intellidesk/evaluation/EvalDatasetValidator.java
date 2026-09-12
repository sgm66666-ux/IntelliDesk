package com.intellidesk.evaluation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 8 Wave 1 dataset validation/structuring.
 *
 * A question entry has the schema:
 *   id, question, relevant_documents[], relevant_chunks[], reference_answer,
 *   relevance_notes, qualified_quality_question
 *
 * Rules:
 *  - id unique, non-blank.
 *  - question non-blank.
 *  - qualified_quality_question == true  => must have >=1 relevant chunk.
 *  - qualified_quality_question == false => must have 0 relevant chunks (adversarial/no-answer).
 *  - relevant chunk ids must use the stable logical format "docLogicalId|ordinal"
 *    (no DB auto-increment ids).
 *  - relevant documents non-empty iff qualified.
 */
public final class EvalDatasetValidator {

    public record Question(
            String id,
            String question,
            List<String> relevantDocuments,
            List<String> relevantChunks,
            String referenceAnswer,
            String relevanceNotes,
            boolean qualifiedQualityQuestion) {
    }

    public record Validation(boolean valid, List<String> errors,
                             List<Question> questions,
                             int qualityCount, int adversarialCount) {
    }

    private EvalDatasetValidator() {
    }

    public static String STABLE_CHUNK_ID_SEPARATOR = "|";

    public static Validation validate(List<Map<String, Object>> rawQuestions) {
        List<String> errors = new java.util.ArrayList<>();
        List<Question> questions = new java.util.ArrayList<>();
        Set<String> ids = new java.util.LinkedHashSet<>();
        int quality = 0, adversarial = 0;

        for (Map<String, Object> m : rawQuestions) {
            String id = str(m.get("id"));
            String question = str(m.get("question"));
            if (id == null || question == null) {
                errors.add("question missing id or question text");
                continue;
            }
            if (!ids.add(id)) {
                errors.add("duplicate question id: " + id);
            }

            List<String> docs = strList(m.get("relevant_documents"));
            List<String> chunks = strList(m.get("relevant_chunks"));
            String refAnswer = str(m.getOrDefault("reference_answer", ""));
            String notes = str(m.getOrDefault("relevance_notes", ""));
            boolean qualified = false;
            Object qq = m.get("qualified_quality_question");
            if (qq instanceof Boolean b) {
                qualified = b;
            } else if (qq instanceof String s) {
                qualified = Boolean.parseBoolean(s);
            } else {
                errors.add("question " + id + " has invalid qualified_quality_question");
            }

            if (qualified) {
                quality++;
                if (chunks.isEmpty()) {
                    errors.add("qualified question '" + id + "' must have >=1 relevant_chunk");
                }
                if (docs.isEmpty()) {
                    errors.add("qualified question '" + id + "' must have >=1 relevant document");
                }
            } else {
                adversarial++;
                if (!chunks.isEmpty()) {
                    errors.add("non-qualified question '" + id + "' must have 0 relevant_chunks (got " + chunks.size() + ")");
                }
            }

            // stable chunk id format check
            for (String c : chunks) {
                if (!c.contains(STABLE_CHUNK_ID_SEPARATOR)) {
                    errors.add("question '" + id + "' chunk id uses unstable/DB id: " + c);
                }
            }

            questions.add(new Question(id, question, docs, chunks, refAnswer, notes, qualified));
        }

        return new Validation(errors.isEmpty(), errors, questions, quality, adversarial);
    }

    public static Map<String, Question> byId(List<Question> questions) {
        Map<String, Question> map = new LinkedHashMap<>();
        for (Question q : questions) {
            map.put(q.id(), q);
        }
        return map;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        List<String> out = new java.util.ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }
}