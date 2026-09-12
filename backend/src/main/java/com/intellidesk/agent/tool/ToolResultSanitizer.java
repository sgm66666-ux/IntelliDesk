package com.intellidesk.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ToolResultSanitizer {

    private static final Logger log = LoggerFactory.getLogger(ToolResultSanitizer.class);

    private static final String TRUNCATED_SUFFIX = "\n(truncated)";

    private final int maxChars;

    public ToolResultSanitizer(AgentProperties agentProperties) {
        this.maxChars = agentProperties.getToolMaxResultChars();
        log.info("ToolResultSanitizer initialized with maxChars={}", maxChars);
    }

    public String sanitize(String rawContent) {
        if (rawContent == null) {
            return "";
        }

        String cleaned = cleanSensitiveInfo(rawContent);

        if (cleaned.length() <= maxChars) {
            return cleaned;
        }

        return cleaned.substring(0, maxChars - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
    }

    private String cleanSensitiveInfo(String content) {
        return content
                .replaceAll("(?i)(SQL\\s*exception|SQLException|SQL\\.SyntaxErrorException)[^\\n]*", "[internal error]")
                .replaceAll("\\s+at\\s+[\\w.$]+\\([^)]*\\)", "")
                .replaceAll("(?i)Caused\\s+by:[^\\n]*", "")
                .replaceAll("(?i)([A-Z]:\\\\[\\w.\\\\-]+|/[\\w./-]+/\\w+\\.\\w+)", "[internal path]")
                .replaceAll("\\s{3,}", "\n\n")
                .trim();
    }
}