package com.fourth.ykd.ai.rag;

import com.fourth.ykd.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

import jakarta.annotation.PostConstruct;

/**
 * RAG 知识库构建器 — ETL 管道。
 *
 * <p>文档加载 → 文本分割 → 向量嵌入 → 向量存储
 *
 * <p>支持格式（复用已有 POI + PDFBox）：
 * TXT / MD — TextReader、PDF — PDFBox、DOCX — Apache POI（含表格提取）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagKnowledgeBuilder {

    private final VectorStore ragVectorStore;
    private final RagVectorStoreConfig config;
    private final RagIngestManifest manifest;

    /** 获取内部使用的 VectorStore 实例，供 Controller 层在摄入后手动触发持久化。 */
    public VectorStore getVectorStore() {
        return ragVectorStore;
    }

    @PostConstruct
    void autoIngestOnStartup() {
        if (config.isForceRebuild()) {
            log.info("[RAG][AUTO_INGEST] forceRebuild=true，清空清单并全量重建");
            manifest.clear();
        }

        Path docsPath = Path.of(config.getDocumentBasePath());
        if (!Files.isDirectory(docsPath)) {
            log.info("[RAG][AUTO_INGEST][SKIP] 目录不存在: {}", docsPath.toAbsolutePath());
            return;
        }
        try {
            int total = ingestDirectory(docsPath);
            // 清理清单中磁盘已不存在的文件条目（手动删除等场景）
            manifest.removeOrphanedEntries(docsPath);
            log.info("[RAG][AUTO_INGEST][DONE] 本次摄入 {} 个文本块, "
                    + "知识库共 {} 个文件 / {} 个文本块",
                    total, manifest.getIngestedFileCount(), manifest.getTotalChunks());
        } catch (Exception e) {
            log.warn("[RAG][AUTO_INGEST][FAILED] reason={}", e.getMessage());
        }
    }

    /**
     * 增量摄入目录：跳过已在清单中且未修改的文件。
     */
    public int ingestDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new BusinessException(40001, "知识库目录不存在: " + directory);
        }

        int newChunks = 0;
        int skippedFiles = 0;

        try (var files = Files.list(directory)) {
            List<Path> supported = files
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedFormat)
                    .toList();

            log.info("[RAG][AUTO_INGEST][SCAN] 扫描到 {} 个支持文件, "
                    + "清单已有 {} 个文件",
                    supported.size(), manifest.getIngestedFileCount());

            for (Path file : supported) {
                try {
                    String fileName = file.getFileName().toString();
                    String fileHash = computeFileHash(file);

                    if (!manifest.needsIngestion(fileName, fileHash)) {
                        skippedFiles++;
                        log.debug("[RAG][AUTO_INGEST][SKIP] {} (未变更)", fileName);
                        continue;
                    }

                    int chunks = ingest(file, fileHash);  // 复用已计算的哈希
                    manifest.markIngested(fileName, fileHash, chunks);
                    newChunks += chunks;
                } catch (Exception e) {
                    log.warn("[RAG][AUTO_INGEST][SKIP_FAILED] fileName={}, reason={}",
                            file.getFileName(), e.getMessage());
                }
            }
        }

        log.info("[RAG][AUTO_INGEST][SUMMARY] 新摄入={}块, 跳过={}个文件, "
                + "知识库总计={}块/{}个文件",
                newChunks, skippedFiles,
                manifest.getTotalChunks(), manifest.getIngestedFileCount());

        if (newChunks > 0) {
            config.persistVectorStore(ragVectorStore);
        }

        return newChunks;
    }

    /**
     * 摄入单个文件到知识库（自动计算哈希）。
     */
    public int ingest(Path filePath) {
        return ingest(filePath, null);
    }

    /**
     * 摄入单个文件到知识库。
     *
     * @param filePath  文件路径
     * @param knownHash 已知的文件 SHA-256 哈希值（从调用方传入，避免重复 IO）；
     *                  为 null 时自动计算
     */
    public int ingest(Path filePath, String knownHash) {
        if (!Files.isRegularFile(filePath)) {
            throw new BusinessException(40001, "知识库文件不存在: " + filePath);
        }

        String fileName = filePath.getFileName().toString();
        log.info("[RAG][BUILD][START] fileName={}", fileName);

        String rawText = extractText(filePath, fileName);
        if (rawText == null || rawText.isBlank()) {
            log.warn("[RAG][BUILD][EMPTY] fileName={}", fileName);
            return 0;
        }

        // 复用调用方传入的哈希值，避免重复读取文件
        String fileHash = knownHash != null ? knownHash : computeFileHash(filePath);

        Document document = Document.builder()
                .text(rawText)
                .metadata("file_name", fileName)
                .metadata("file_path", filePath.toString())
                .build();

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(config.getChunkSize())
                .withMinChunkSizeChars(50)
                .withMinChunkLengthToEmbed(50)
                .withMaxNumChunks(500)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(List.of(document));
        if (chunks.isEmpty()) {
            log.warn("[RAG][BUILD][TOO_SHORT] fileName={}, textLen={}, 内容不足最小chunk长度，已跳过",
                    fileName, rawText.length());
            return 0;
        }

        String ingestedAt = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        for (Document chunk : chunks) {
            chunk.getMetadata().put("file_name", fileName);
            chunk.getMetadata().put("file_path", filePath.toString());
            chunk.getMetadata().put("file_hash", fileHash);
            chunk.getMetadata().put("ingested_at", ingestedAt);
        }

        int chunkCount = chunks.size();
        ragVectorStore.add(chunks);
        log.info("[RAG][BUILD][SUCCESS] fileName={}, chunks={}", fileName, chunkCount);
        return chunkCount;
    }

    // ==================== 文本提取 ====================

    private String extractText(Path filePath, String fileName) {
        String lowerName = fileName.toLowerCase();
        try {
            if (lowerName.endsWith(".txt") || lowerName.endsWith(".md")) {
                TextReader reader = new TextReader(new FileSystemResource(filePath.toFile()));
                List<Document> docs = reader.get();
                return docs != null && !docs.isEmpty() ? docs.get(0).getText() : null;
            }
            if (lowerName.endsWith(".pdf")) {
                return extractPdfText(filePath);
            }
            if (lowerName.endsWith(".docx")) {
                return extractDocxText(filePath);
            }
            log.debug("[RAG][BUILD][UNSUPPORTED] fileName={}", fileName);
            return null;
        } catch (Exception e) {
            log.warn("[RAG][BUILD][EXTRACT_FAILED] fileName={}, reason={}",
                    fileName, e.getMessage());
            return null;
        }
    }

    private String extractPdfText(Path filePath) throws IOException {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return truncateIfNeeded(stripper.getText(document));
        }
    }

    private String extractDocxText(Path filePath) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(
                new ByteArrayInputStream(Files.readAllBytes(filePath)))) {
            StringBuilder sb = new StringBuilder();

            // 提取段落文本
            doc.getParagraphs().forEach(p -> {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text).append('\n');
                }
            });

            // 提取表格内容（API 参数表、配置表等）
            doc.getTables().forEach(table -> {
                sb.append("\n【表格】\n");
                table.getRows().forEach(row -> {
                    StringBuilder rowText = new StringBuilder();
                    row.getTableCells().forEach(cell ->
                            rowText.append(cell.getText().trim()).append(" | "));
                    if (!rowText.isEmpty()) {
                        sb.append(rowText).append('\n');
                    }
                });
            });

            return truncateIfNeeded(sb.toString());
        }
    }

    private String truncateIfNeeded(String text) {
        int maxLen = config.getMaxTextLength();
        if (maxLen > 0 && text.length() > maxLen) {
            return text.substring(0, maxLen) + "\n...(内容已截断)";
        }
        return text;
    }

    // ==================== 辅助方法 ====================

    private boolean isSupportedFormat(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".pdf") || name.endsWith(".docx")
                || name.endsWith(".txt") || name.endsWith(".md");
    }

    private String computeFileHash(Path filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(filePath)));
        } catch (Exception e) {
            return String.valueOf(filePath.toFile().lastModified());
        }
    }
}