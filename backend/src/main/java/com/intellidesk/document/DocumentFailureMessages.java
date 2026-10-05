package com.intellidesk.document;

/** Public/persisted task summaries must not contain raw parser/storage exception text. */
final class DocumentFailureMessages {
    private DocumentFailureMessages() { }
    static String describe(String code) {
        if (code == null) return "Document processing failed";
        return switch (code) {
            case "KB_NOT_FOUND" -> "Knowledge base not found";
            case "STORAGE_UNAVAILABLE", "DOWNLOAD_FAILED" -> "Document storage unavailable";
            case "UNSUPPORTED_FORMAT" -> "Unsupported document format";
            case "CHUNK_FAILED", "EMPTY_CHUNKS" -> "Document chunk processing failed";
            case "LEASE_EXPIRED_MAX_ATTEMPTS" -> "Processing lease expired after max attempts";
            case "LEASE_EXPIRED" -> "Processing lease expired, retrying";
            default -> "Document processing failed; consult failureCode and taskId";
        };
    }
}
