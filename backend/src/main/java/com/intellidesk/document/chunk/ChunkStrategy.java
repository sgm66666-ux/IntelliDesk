package com.intellidesk.document.chunk;

import com.intellidesk.document.parser.ParsedDocument;

import java.util.List;

public interface ChunkStrategy {

    ChunkStrategyType type();

    List<ChunkDraft> split(ParsedDocument document, ChunkConfig config);
}
