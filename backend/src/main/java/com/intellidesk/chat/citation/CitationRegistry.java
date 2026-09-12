package com.intellidesk.chat.citation;

import java.util.*;

/**
 * Immutable server-authoritative citation registry.
 * Maps citationId to Citation. Built from RagContext entries only.
 */
public final class CitationRegistry {

    private final Map<Integer, Citation> citations;

    private CitationRegistry(Map<Integer, Citation> citations) {
        this.citations = Map.copyOf(citations);
    }

    public static CitationRegistry from(List<Citation> citations) {
        Map<Integer, Citation> map = new LinkedHashMap<>();
        for (Citation c : citations) {
            if (map.containsKey(c.citationId())) {
                throw new IllegalArgumentException("Duplicate citation ID: " + c.citationId());
            }
            map.put(c.citationId(), c);
        }
        return new CitationRegistry(map);
    }

    public static CitationRegistry empty() {
        return new CitationRegistry(Map.of());
    }

    public Citation find(int citationId) {
        return citations.get(citationId);
    }

    public boolean contains(int citationId) {
        return citations.containsKey(citationId);
    }

    public int size() {
        return citations.size();
    }

    public boolean isEmpty() {
        return citations.isEmpty();
    }

    public Collection<Citation> all() {
        return citations.values();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CitationRegistry that)) return false;
        return citations.equals(that.citations);
    }

    @Override
    public int hashCode() {
        return citations.hashCode();
    }
}