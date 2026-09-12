package com.intellidesk.chat.citation;

import java.util.List;

/**
 * Result of citation validation against the registry.
 */
public record CitationValidationResult(
        List<Integer> validCitationIds,
        List<Integer> hallucinatedCitationIds,
        boolean hasHallucinations
) {
    public CitationValidationResult {
        validCitationIds = List.copyOf(validCitationIds);
        hallucinatedCitationIds = List.copyOf(hallucinatedCitationIds);
    }

    public static CitationValidationResult empty() {
        return new CitationValidationResult(List.of(), List.of(), false);
    }
}