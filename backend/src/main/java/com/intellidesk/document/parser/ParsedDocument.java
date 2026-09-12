package com.intellidesk.document.parser;

import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Getter
public class ParsedDocument {

    private final Map<String, Object> metadata;
    private final List<ParsedSegment> segments;

    public ParsedDocument(Map<String, Object> metadata, List<ParsedSegment> segments) {
        this.metadata = metadata != null ? metadata : Collections.emptyMap();
        this.segments = segments != null ? segments : Collections.emptyList();
    }
}
