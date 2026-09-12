package com.intellidesk.chat.citation;

import com.intellidesk.chat.context.ContextEntry;
import com.intellidesk.chat.context.ContextProperties;
import com.intellidesk.chat.context.RagContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a CitationRegistry from RagContext entries.
 * Citations are server-authoritative — only built from ContextEntry data.
 */
@Component
public class CitationAssembler {

    private final ContextProperties contextProperties;

    public CitationAssembler(ContextProperties contextProperties) {
        this.contextProperties = contextProperties;
    }

    /**
     * Build citation registry from RagContext entries.
     * Each ContextEntry becomes a Citation with truncated content.
     */
    public CitationRegistry assemble(RagContext context) {
        if (context == null || context.isEmpty()) {
            return CitationRegistry.empty();
        }

        int maxChars = contextProperties.getCitationContentMaxChars();
        List<Citation> citations = new ArrayList<>();

        for (ContextEntry entry : context.entries()) {
            String truncatedContent = truncate(entry.content(), maxChars);
            citations.add(new Citation(
                    entry.citationId(),
                    entry.documentId(),
                    entry.documentName(),
                    entry.chunkId(),
                    truncatedContent,
                    entry.score(),
                    entry.pageNumber()
            ));
        }

        return CitationRegistry.from(citations);
    }

    private String truncate(String content, int maxChars) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars);
    }
}