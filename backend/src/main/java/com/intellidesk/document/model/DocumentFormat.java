package com.intellidesk.document.model;

import lombok.Getter;

@Getter
public enum DocumentFormat {
    PDF("pdf"),
    MARKDOWN("md"),
    TEXT("txt");

    private final String extension;

    DocumentFormat(String extension) {
        this.extension = extension;
    }

    public static DocumentFormat fromExtension(String extension) {
        if (extension == null) return null;
        String normalized = extension.toLowerCase().trim();
        for (DocumentFormat format : values()) {
            if (format.extension.equals(normalized)) {
                return format;
            }
        }
        // Also handle "markdown" -> MARKDOWN
        if ("markdown".equals(normalized)) {
            return MARKDOWN;
        }
        throw new IllegalArgumentException("Unsupported extension: " + extension);
    }
}
