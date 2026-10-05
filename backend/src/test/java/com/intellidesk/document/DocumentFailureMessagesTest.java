package com.intellidesk.document;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class DocumentFailureMessagesTest {
    @Test void unknownExternalErrorNamesCannotBecomePublicExceptionBody() {
        assertThat(DocumentFailureMessages.describe("synthetic-password-marker"))
            .isEqualTo("Document processing failed; consult failureCode and taskId");
    }
    @Test void typedErrorsHaveShortSafeSummaries() {
        assertThat(DocumentFailureMessages.describe("STORAGE_UNAVAILABLE")).isEqualTo("Document storage unavailable");
        assertThat(DocumentFailureMessages.describe(null)).isEqualTo("Document processing failed");
        assertThat(DocumentFailureMessages.describe("KB_NOT_FOUND")).isEqualTo("Knowledge base not found");
    }
}
