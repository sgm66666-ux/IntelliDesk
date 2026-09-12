package com.intellidesk.document.parser;

import com.intellidesk.document.model.DocumentFormat;

import java.io.InputStream;

public interface DocumentParser {

    DocumentFormat format();

    ParsedDocument parse(InputStream input, ParseContext context) throws DocumentParseException;
}
