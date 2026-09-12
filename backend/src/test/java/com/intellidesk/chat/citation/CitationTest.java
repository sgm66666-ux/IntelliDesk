package com.intellidesk.chat.citation;

import com.intellidesk.chat.context.ContextEntry;
import com.intellidesk.chat.context.ContextProperties;
import com.intellidesk.chat.context.RagContext;
import com.intellidesk.retrieval.ScoreType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Citation")
class CitationTest {

    private ContextProperties contextProperties;
    private CitationAssembler assembler;
    private CitationValidator validator;

    @BeforeEach
    void setUp() {
        contextProperties = new ContextProperties();
        contextProperties.setCitationContentMaxChars(1000);
        assembler = new CitationAssembler(contextProperties);
        validator = new CitationValidator();
    }

    private ContextEntry makeEntry(int citationId, Long chunkId, Long docId, String content) {
        return new ContextEntry(citationId, chunkId, docId, "doc.pdf", content,
                0, "", 0.9f, ScoreType.RRF, null);
    }

    private ContextEntry makeEntry(int citationId, Long chunkId, Long docId, String content, Integer pageNumber) {
        return new ContextEntry(citationId, chunkId, docId, "doc.pdf", content,
                0, "", 0.9f, ScoreType.RRF, pageNumber);
    }

    @Nested
    @DisplayName("CitationRegistry")
    class RegistryTests {

        @Test
        @DisplayName("build from citations")
        void buildFromCitations() {
            CitationRegistry registry = CitationRegistry.from(List.of(
                    new Citation(1, 1L, "doc.pdf", 10L, "content", 0.9f, null)));

            assertThat(registry.size()).isEqualTo(1);
            assertThat(registry.contains(1)).isTrue();
            assertThat(registry.contains(2)).isFalse();
            assertThat(registry.find(1).chunkId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("empty registry")
        void emptyRegistry() {
            CitationRegistry registry = CitationRegistry.empty();
            assertThat(registry.isEmpty()).isTrue();
            assertThat(registry.size()).isZero();
        }

        @Test
        @DisplayName("duplicate citation ID throws")
        void duplicateIdThrows() {
            assertThatThrownBy(() -> CitationRegistry.from(List.of(
                    new Citation(1, 1L, "a.pdf", 10L, "a", 0.9f, null),
                    new Citation(1, 1L, "b.pdf", 20L, "b", 0.8f, null))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("immutable")
        void immutable() {
            CitationRegistry registry = CitationRegistry.from(List.of(
                    new Citation(1, 1L, "doc.pdf", 10L, "content", 0.9f, null)));

            assertThatThrownBy(() -> registry.all().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("CitationAssembler")
    class AssemblerTests {

        @Test
        @DisplayName("assemble from RagContext")
        void assembleFromContext() {
            RagContext ctx = new RagContext(
                    List.of(makeEntry(1, 10L, 1L, "test content")),
                    10, 4096);

            CitationRegistry registry = assembler.assemble(ctx);

            assertThat(registry.size()).isEqualTo(1);
            Citation c = registry.find(1);
            assertThat(c.chunkId()).isEqualTo(10L);
            assertThat(c.documentId()).isEqualTo(1L);
            assertThat(c.documentName()).isEqualTo("doc.pdf");
            assertThat(c.content()).isEqualTo("test content");
        }

        @Test
        @DisplayName("empty context produces empty registry")
        void emptyContext() {
            RagContext ctx = new RagContext(List.of(), 0, 4096);
            CitationRegistry registry = assembler.assemble(ctx);
            assertThat(registry.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("null context produces empty registry")
        void nullContext() {
            CitationRegistry registry = assembler.assemble(null);
            assertThat(registry.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("stable citation IDs")
        void stableCitationIds() {
            RagContext ctx = new RagContext(
                    List.of(
                            makeEntry(1, 10L, 1L, "A"),
                            makeEntry(2, 20L, 1L, "B")),
                    20, 4096);

            CitationRegistry registry = assembler.assemble(ctx);
            assertThat(registry.find(1).chunkId()).isEqualTo(10L);
            assertThat(registry.find(2).chunkId()).isEqualTo(20L);
        }

        @Test
        @DisplayName("citation content truncated")
        void contentTruncated() {
            contextProperties.setCitationContentMaxChars(10);
            String longContent = "This is a very long content that exceeds the limit";
            RagContext ctx = new RagContext(
                    List.of(makeEntry(1, 10L, 1L, longContent)),
                    50, 4096);

            CitationRegistry registry = assembler.assemble(ctx);
            assertThat(registry.find(1).content().length()).isEqualTo(10);
        }

        @Test
        @DisplayName("no fake pageNumber")
        void noFakePageNumber() {
            RagContext ctx = new RagContext(
                    List.of(makeEntry(1, 10L, 1L, "content")),
                    10, 4096);

            CitationRegistry registry = assembler.assemble(ctx);
            assertThat(registry.find(1).pageNumber()).isNull();
        }

        @Test
        @DisplayName("pageNumber from context entry")
        void pageNumberFromEntry() {
            RagContext ctx = new RagContext(
                    List.of(makeEntry(1, 10L, 1L, "content", 5)),
                    10, 4096);

            CitationRegistry registry = assembler.assemble(ctx);
            assertThat(registry.find(1).pageNumber()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("CitationValidator")
    class ValidatorTests {

        @Test
        @DisplayName("valid [1]")
        void validSingle() {
            CitationRegistry registry = CitationRegistry.from(List.of(
                    new Citation(1, 1L, "doc.pdf", 10L, "content", 0.9f, null)));

            CitationValidationResult result = validator.validate("标准为 500 元。[1]", registry);

            assertThat(result.validCitationIds()).containsExactly(1);
            assertThat(result.hallucinatedCitationIds()).isEmpty();
            assertThat(result.hasHallucinations()).isFalse();
        }

        @Test
        @DisplayName("multiple [1][2]")
        void multipleValid() {
            CitationRegistry registry = CitationRegistry.from(List.of(
                    new Citation(1, 1L, "a.pdf", 10L, "a", 0.9f, null),
                    new Citation(2, 1L, "b.pdf", 20L, "b", 0.8f, null)));

            CitationValidationResult result = validator.validate("...[1]...[2]", registry);

            assertThat(result.validCitationIds()).containsExactly(1, 2);
        }

        @Test
        @DisplayName("duplicate references deduped")
        void duplicateReferences() {
            CitationRegistry registry = CitationRegistry.from(List.of(
                    new Citation(1, 1L, "doc.pdf", 10L, "content", 0.9f, null)));

            CitationValidationResult result = validator.validate("[1]...[1]", registry);

            assertThat(result.validCitationIds()).containsExactly(1);
        }

        @Test
        @DisplayName("hallucinated [99]")
        void hallucinated() {
            CitationRegistry registry = CitationRegistry.from(List.of(
                    new Citation(1, 1L, "doc.pdf", 10L, "content", 0.9f, null)));

            CitationValidationResult result = validator.validate("[99]", registry);

            assertThat(result.validCitationIds()).isEmpty();
            assertThat(result.hallucinatedCitationIds()).containsExactly(99);
            assertThat(result.hasHallucinations()).isTrue();
        }

        @Test
        @DisplayName("mixed valid/hallucinated")
        void mixedValidAndHallucinated() {
            CitationRegistry registry = CitationRegistry.from(List.of(
                    new Citation(1, 1L, "a.pdf", 10L, "a", 0.9f, null),
                    new Citation(2, 1L, "b.pdf", 20L, "b", 0.8f, null)));

            CitationValidationResult result = validator.validate("[1] [99] [2]", registry);

            assertThat(result.validCitationIds()).containsExactly(1, 2);
            assertThat(result.hallucinatedCitationIds()).containsExactly(99);
            assertThat(result.hasHallucinations()).isTrue();
        }

        @Test
        @DisplayName("no citation")
        void noCitation() {
            CitationRegistry registry = CitationRegistry.from(List.of(
                    new Citation(1, 1L, "doc.pdf", 10L, "content", 0.9f, null)));

            CitationValidationResult result = validator.validate("普通回答，没有引用。", registry);

            assertThat(result.validCitationIds()).isEmpty();
            assertThat(result.hallucinatedCitationIds()).isEmpty();
            assertThat(result.hasHallucinations()).isFalse();
        }

        @Test
        @DisplayName("empty registry")
        void emptyRegistry() {
            CitationValidationResult result = validator.validate("[1]", CitationRegistry.empty());

            assertThat(result.validCitationIds()).isEmpty();
            assertThat(result.hallucinatedCitationIds()).containsExactly(1);
            assertThat(result.hasHallucinations()).isTrue();
        }

        @Test
        @DisplayName("null answer returns empty")
        void nullAnswer() {
            CitationRegistry registry = CitationRegistry.from(List.of(
                    new Citation(1, 1L, "doc.pdf", 10L, "content", 0.9f, null)));

            CitationValidationResult result = validator.validate(null, registry);
            assertThat(result.validCitationIds()).isEmpty();
        }

        @Test
        @DisplayName("huge citation ID overflow guarded")
        void hugeCitationId() {
            CitationRegistry registry = CitationRegistry.from(List.of(
                    new Citation(1, 1L, "doc.pdf", 10L, "content", 0.9f, null)));

            CitationValidationResult result = validator.validate("[99999999999999999999]", registry);

            // Should not crash, huge ID is out of range
            assertThat(result.validCitationIds()).isEmpty();
        }

        @Test
        @DisplayName("malformed bracket not treated as citation")
        void malformedBracket() {
            CitationRegistry registry = CitationRegistry.from(List.of(
                    new Citation(1, 1L, "doc.pdf", 10L, "content", 0.9f, null)));

            // Array literal style brackets should not be treated as citation
            CitationValidationResult result = validator.validate("arr[0]", registry);

            // [0] is not a valid citation (ID must be >= 1)
            assertThat(result.validCitationIds()).isEmpty();
        }
    }
}