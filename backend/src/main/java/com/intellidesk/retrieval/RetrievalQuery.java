package com.intellidesk.retrieval;

/**
 * Retrieval query with user input and server-resolved scope.
 * Generation correctness is enforced per-document in SQL via
 * c.embedding_generation = rt.generation — no query-level generation parameter.
 */
public record RetrievalQuery(
        String query,
        RetrievalScope scope
) {
    public RetrievalQuery {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be empty");
        }
        if (query.length() > 2000) {
            throw new IllegalArgumentException("query must be <= 2000 characters");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
    }
}