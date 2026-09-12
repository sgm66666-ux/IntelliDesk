package com.intellidesk.chat.citation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates citation references in LLM answer text against the server-authoritative registry.
 * Extracts [N] patterns, checks against registry, flags hallucinated IDs.
 */
@Component
public class CitationValidator {

    private static final Logger log = LoggerFactory.getLogger(CitationValidator.class);

    /**
     * Regex for citation pattern: [positive integer]
     * Must not be inside brackets that indicate arrays, markdown links, or code blocks.
     */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

    /**
     * Maximum allowed citation ID to prevent overflow/DoS.
     */
    private static final int MAX_CITATION_ID = 9999;

    /**
     * Validate citation references in answer text against the registry.
     * @param answerText the LLM-generated answer text
     * @param registry the server-authoritative citation registry
     * @return validation result with valid and hallucinated citation IDs
     */
    public CitationValidationResult validate(String answerText, CitationRegistry registry) {
        if (answerText == null || answerText.isEmpty()) {
            return CitationValidationResult.empty();
        }
        if (registry == null || registry.isEmpty()) {
            // If registry is empty and there are citation patterns, they're all hallucinated
            List<Integer> extracted = extractCitationIds(answerText);
            if (!extracted.isEmpty()) {
                log.warn("Hallucinated citations found but registry is empty: {}", extracted);
                return new CitationValidationResult(List.of(), extracted, true);
            }
            return CitationValidationResult.empty();
        }

        // Extract all citation IDs from answer text
        List<Integer> extracted = extractCitationIds(answerText);

        Set<Integer> validSet = new LinkedHashSet<>();
        Set<Integer> hallucinatedSet = new LinkedHashSet<>();

        for (int id : extracted) {
            if (registry.contains(id)) {
                validSet.add(id);
            } else {
                hallucinatedSet.add(id);
            }
        }

        if (!hallucinatedSet.isEmpty()) {
            log.warn("Hallucinated citations detected: {}", hallucinatedSet);
        }

        return new CitationValidationResult(
                List.copyOf(validSet),
                List.copyOf(hallucinatedSet),
                !hallucinatedSet.isEmpty());
    }

    private List<Integer> extractCitationIds(String text) {
        List<Integer> ids = new ArrayList<>();
        Matcher matcher = CITATION_PATTERN.matcher(text);

        while (matcher.find()) {
            try {
                int id = Integer.parseInt(matcher.group(1));
                if (id > 0 && id <= MAX_CITATION_ID) {
                    ids.add(id);
                } else {
                    log.debug("Citation ID out of valid range: {}", id);
                }
            } catch (NumberFormatException e) {
                log.debug("Failed to parse citation ID: {}", matcher.group(1));
            }
        }

        return ids;
    }
}