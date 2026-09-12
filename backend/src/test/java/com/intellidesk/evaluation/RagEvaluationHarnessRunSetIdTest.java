package com.intellidesk.evaluation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link RagEvaluationHarness#resolveRunSetId()}.
 *
 * Verifies the external override semantics required by Phase 8 Wave 1 Final
 * Independent Review MUST_FIX: harness run-set identity must be configurable
 * via {@code -DrunSetId=<id>} while keeping a stable default and rejecting an
 * empty value.
 */
class RagEvaluationHarnessRunSetIdTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(RagEvaluationHarness.RUN_SET_ID_PROPERTY);
    }

    @Test
    void defaultRunSetIdWhenPropertyAbsent() {
        System.clearProperty(RagEvaluationHarness.RUN_SET_ID_PROPERTY);
        assertEquals("implementation-compensation-001", RagEvaluationHarness.resolveRunSetId());
    }

    @Test
    void overriddenRunSetIdWhenPropertyPresent() {
        System.setProperty(RagEvaluationHarness.RUN_SET_ID_PROPERTY, "independent-review-001");
        assertEquals("independent-review-001", RagEvaluationHarness.resolveRunSetId());
    }

    @Test
    void rejectsEmptyPropertyValue() {
        System.setProperty(RagEvaluationHarness.RUN_SET_ID_PROPERTY, "");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, RagEvaluationHarness::resolveRunSetId);
        assertEquals("System property -DrunSetId must not be empty", ex.getMessage());
    }

    @Test
    void rejectsBlankPropertyValue() {
        System.setProperty(RagEvaluationHarness.RUN_SET_ID_PROPERTY, "   ");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, RagEvaluationHarness::resolveRunSetId);
        assertEquals("System property -DrunSetId must not be empty", ex.getMessage());
    }
}
