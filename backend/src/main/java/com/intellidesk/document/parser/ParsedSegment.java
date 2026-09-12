package com.intellidesk.document.parser;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;

@Getter
@Builder
public class ParsedSegment {

    private final String text;
    private final Integer pageNumber;
    private final String sectionPath;
    private final int startOffset;
    private final int endOffset;
    private final Map<String, Object> metadata;

    public Map<String, Object> getMetadata() {
        return metadata != null ? metadata : Collections.emptyMap();
    }
}
