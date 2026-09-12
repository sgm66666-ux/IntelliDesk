package com.intellidesk.document.parser;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ParseContext {

    private final Long documentId;
    private final String originalFileName;
    private final Long maxExtractedCharacters;
    private final Integer maxPdfPages;
}
