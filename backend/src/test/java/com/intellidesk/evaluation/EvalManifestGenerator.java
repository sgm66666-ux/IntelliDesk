package com.intellidesk.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.intellidesk.document.chunk.ChunkConfig;
import com.intellidesk.document.chunk.ChunkDraft;
import com.intellidesk.document.chunk.ChunkStrategyType;
import com.intellidesk.document.chunk.RecursiveChunkStrategy;
import com.intellidesk.document.parser.MarkdownDocumentParser;
import com.intellidesk.document.parser.ParseContext;
import com.intellidesk.document.parser.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 8 Wave 1 — Phase A: produce the indexed_chunk_manifest from the REAL production
 * parser (MarkdownDocumentParser) + REAL production chunker (RecursiveChunkStrategy) with
 * the FROZEN chunk config. Deterministic, no containers. Not auto-discovered by surefire
 * (class name does not match default test include patterns).
 *
 * Run: mvn -Dtest=EvalManifestGenerator test
 *
 * Output: docs/evaluation/raw/indexed_chunk_manifest.json (canonical)
 */
public class EvalManifestGenerator {

    public static final String CHUNK_SIZE = "1000";
    public static final String CHUNK_OVERLAP = "150";
    public static final String CHUNK_STRATEGY = "RECURSIVE";

    public static String corpusDir() {
        return resolveProjectRoot() + "/docs/evaluation/corpus";
    }

    public static String manifestPath() {
        return resolveProjectRoot() + "/docs/evaluation/raw/indexed_chunk_manifest.json";
    }

    /**
     * Locate the project root by walking up from the current working directory until a
     * directory containing {@code docs/evaluation/corpus} is found. This keeps the harness
     * location-independent (Maven runs from backend/).
     */
    public static String resolveProjectRoot() {
        java.io.File dir = new java.io.File("").getAbsoluteFile();
        while (dir != null) {
            if (new java.io.File(dir, "docs/evaluation/corpus").isDirectory()) {
                return dir.getAbsolutePath().replace('\\', '/');
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("Could not locate project root containing docs/evaluation/corpus");
    }

    @Test
    void generateManifest() throws Exception {
        File corpus = new File(corpusDir());
        assertTrue(corpus.isDirectory(), "corpus dir missing: " + corpus.getAbsolutePath());

        MarkdownDocumentParser parser = new MarkdownDocumentParser();
        RecursiveChunkStrategy chunker = new RecursiveChunkStrategy();
        ChunkConfig config = new ChunkConfig(ChunkStrategyType.RECURSIVE,
                Integer.parseInt(CHUNK_SIZE), Integer.parseInt(CHUNK_OVERLAP));
        ParseContext ctx = ParseContext.builder().build();

        List<Map<String, Object>> documents = new ArrayList<>();
        int totalChunks = 0;

        File[] files = corpus.listFiles((d, name) -> name.endsWith(".md"));
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));

        for (File f : files) {
            byte[] raw;
            try (FileInputStream in = new FileInputStream(f)) {
                raw = in.readAllBytes();
            }
            ParsedDocument parsed = parser.parse(new java.io.ByteArrayInputStream(raw), ctx);
            List<ChunkDraft> chunks = chunker.split(parsed, config);

            List<Map<String, Object>> chunkEntries = new ArrayList<>();
            for (ChunkDraft c : chunks) {
                Map<String, Object> ce = new LinkedHashMap<>();
                ce.put("chunk_ordinal", c.getChunkIndex());
                ce.put("section_path", c.getSectionPath());
                ce.put("start_offset", c.getStartOffset());
                ce.put("end_offset", c.getEndOffset());
                ce.put("character_count", c.getCharacterCount());
                ce.put("content_sha256", EvalHashing.sha256Hex(c.getContent().getBytes(StandardCharsets.UTF_8)));
                ce.put("content", c.getContent());
                chunkEntries.add(ce);
            }
            totalChunks += chunks.size();

            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("logical_document_id", logicalId(f.getName()));
            doc.put("relative_path", f.getName());
            doc.put("source_sha256", EvalHashing.sha256Hex(raw));
            doc.put("chunk_count", chunks.size());
            doc.put("chunks", chunkEntries);
            documents.add(doc);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        Map<String, Object> chunkConfig = new LinkedHashMap<>();
        chunkConfig.put("strategy", CHUNK_STRATEGY);
        chunkConfig.put("size", Integer.parseInt(CHUNK_SIZE));
        chunkConfig.put("overlap", Integer.parseInt(CHUNK_OVERLAP));
        manifest.put("chunk_config", chunkConfig);
        manifest.put("parser", "MARKDOWN");
        manifest.put("total_documents", documents.size());
        manifest.put("total_chunks", totalChunks);
        manifest.put("documents", documents);

        ObjectMapper om = new ObjectMapper();
        om.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false); // we keep our own order
        byte[] jsonBytes = om.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(manifest);
        // write raw first, then compute hash over EXACT bytes
        File out = new File(manifestPath());
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), jsonBytes);

        // verify stable chunk identity: doc|ordinal unique
        Map<String, String> identityToContent = new LinkedHashMap<>();
        for (Map<String, Object> doc : documents) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> chunks = (List<Map<String, Object>>) doc.get("chunks");
            for (Map<String, Object> ce : chunks) {
                String id = doc.get("logical_document_id") + EvalDatasetValidator.STABLE_CHUNK_ID_SEPARATOR + ce.get("chunk_ordinal");
                identityToContent.put(id, (String) ce.get("content_sha256"));
            }
        }
        assertTrue(identityToContent.size() == totalChunks,
                "stable chunk identity must be unique per (doc,ordinal)");

        String indexedHash = EvalHashing.sha256Hex(jsonBytes);
        System.out.println("== indexed chunk manifest ==");
        System.out.println("path: " + manifestPath());
        System.out.println("total_documents: " + documents.size());
        System.out.println("total_chunks: " + totalChunks);
        System.out.println("indexed_chunk_manifest_hash: " + indexedHash);
    }

    static String logicalId(String name) {
        int dot = name.indexOf('.');
        return (dot >= 0 ? name.substring(0, dot) : name).toLowerCase();
    }
}