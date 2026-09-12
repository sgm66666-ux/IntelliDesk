package com.intellidesk.document.parser;

import com.intellidesk.common.BusinessException;
import com.intellidesk.common.ErrorCode;
import com.intellidesk.document.model.DocumentFormat;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ParserRegistry {

    private final Map<DocumentFormat, DocumentParser> parsers;

    public ParserRegistry(List<DocumentParser> parserList) {
        this.parsers = parserList.stream()
                .collect(Collectors.toMap(DocumentParser::format, Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate parser for format: " + a.format());
                        }));
    }

    public DocumentParser getParser(DocumentFormat format) {
        DocumentParser parser = parsers.get(format);
        if (parser == null) {
            throw new BusinessException(ErrorCode.DOCUMENT_TYPE_UNSUPPORTED);
        }
        return parser;
    }
}
