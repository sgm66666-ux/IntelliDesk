package com.intellidesk.document.parser;

import com.intellidesk.document.model.DocumentFormat;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class DocumentFormatDetector {

    private static final int PDF_SIGNATURE_LENGTH = 4;

    public DocumentFormat detect(String originalFilename, String declaredContentType, byte[] bytes) {
        String extension = extractExtension(originalFilename);
        String normalizedMime = declaredContentType != null ? declaredContentType.trim().toLowerCase(Locale.ROOT) : "";

        if (isPdf(bytes, extension, normalizedMime)) {
            return DocumentFormat.PDF;
        }

        if (isMarkdown(extension, normalizedMime)) {
            if (isValidUtf8NoNul(bytes)) {
                return DocumentFormat.MARKDOWN;
            }
            return null;
        }

        if (isPlainText(extension, normalizedMime)) {
            if (isValidUtf8NoNul(bytes)) {
                return DocumentFormat.TEXT;
            }
            return null;
        }

        return null;
    }

    public DocumentFormat detect(String originalFilename, String declaredContentType, InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        return detect(originalFilename, declaredContentType, bytes);
    }

    private boolean isPdf(byte[] bytes, String extension, String normalizedMime) {
        if (!"pdf".equalsIgnoreCase(extension)) {
            return false;
        }
        if (StringUtils.hasText(normalizedMime)
                && !normalizedMime.equals("application/pdf")
                && !normalizedMime.equals("application/octet-stream")) {
            return false;
        }
        return bytes.length >= PDF_SIGNATURE_LENGTH
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F';
    }

    private boolean isMarkdown(String extension, String normalizedMime) {
        if ("md".equalsIgnoreCase(extension) || "markdown".equalsIgnoreCase(extension)) {
            return true;
        }
        if ("txt".equalsIgnoreCase(extension) && normalizedMime.equals("text/markdown")) {
            return true;
        }
        return false;
    }

    private boolean isPlainText(String extension, String normalizedMime) {
        if ("txt".equalsIgnoreCase(extension)) {
            return true;
        }
        if (("md".equalsIgnoreCase(extension) || "markdown".equalsIgnoreCase(extension))
                && (normalizedMime.equals("text/plain") || normalizedMime.equals("application/octet-stream"))) {
            return true;
        }
        return false;
    }

    private boolean isValidUtf8NoNul(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return true;
        }
        for (byte b : bytes) {
            if (b == 0x00) {
                return false;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private String extractExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        String name = filename.trim();
        int lastDot = name.lastIndexOf('.');
        if (lastDot < 0 || lastDot == name.length() - 1) {
            return "";
        }
        return name.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }
}
