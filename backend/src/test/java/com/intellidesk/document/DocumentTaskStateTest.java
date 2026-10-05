package com.intellidesk.document;

import com.intellidesk.document.model.DocumentTaskStatus;
import org.junit.jupiter.api.Test;
import static com.intellidesk.document.model.DocumentTaskStatus.*;
import static org.assertj.core.api.Assertions.*;

class DocumentTaskStateTest {
    @Test void normalAndRetryFlowHaveLegalTransitions() {
        assertThat(PENDING.canTransitionTo(QUEUED)).isTrue();
        assertThat(QUEUED.canTransitionTo(PROCESSING)).isTrue();
        assertThat(PROCESSING.canTransitionTo(RETRY_WAIT)).isTrue();
        assertThat(RETRY_WAIT.canTransitionTo(QUEUED)).isTrue();
        assertThat(PROCESSING.canTransitionTo(SUCCEEDED)).isTrue();
        assertThat(PROCESSING.canTransitionTo(DEAD)).isTrue();
    }
    @Test void completedAndDeadTasksNeverRestart() {
        for (var terminal : new DocumentTaskStatus[]{SUCCEEDED, DEAD, CANCELLED})
            for (var target : DocumentTaskStatus.values()) assertThat(terminal.canTransitionTo(target)).isFalse();
    }
    @Test void invalidBackwardsOrSkippedTransitionsAreRejected() {
        assertThat(QUEUED.canTransitionTo(PENDING)).isFalse();
        assertThat(PENDING.canTransitionTo(SUCCEEDED)).isFalse();
        assertThat(RETRY_WAIT.canTransitionTo(SUCCEEDED)).isFalse();
        assertThat(PROCESSING.canTransitionTo(null)).isFalse();
        assertThat(PROCESSING.canTransitionTo(PROCESSING)).isFalse();
    }
    @Test void consumerAndSqlShareTheSameClaimableStates() {
        assertThat(DocumentTaskStatus.claimableValues()).containsExactly("PENDING","QUEUED","RETRY_WAIT");
    }
}
