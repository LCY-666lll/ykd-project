package com.fourth.ykd.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RAG 核心服务：文档切块、存入向量库、语义检索。
 */
@Slf4j
@Service
public class RagService {

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;
    private static final int TOP_K = 3;

    private final SQLiteVectorStore vectorStore;

    public RagService(SQLiteVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 存入文档：切块 → 向量化 → 存储。
     * @param content  文件解析后的纯文本
     * @param userId   用户ID（用于多用户隔离）
     * @param fileName 文件名
     */
    public void ingestDocument(String content, String userId, String fileName) {
        if (content == null || content.isBlank()) {
            return;
        }
        try {
            String docId = UUID.randomUUID().toString();
            List<Document> chunks = splitIntoChunks(content, userId, fileName, docId);
            vectorStore.add(chunks);
            log.info("[RAG][INGEST] userId={}, fileName={}, chunks={}", userId, fileName, chunks.size());
        } catch (Exception e) {
            log.error("[RAG][INGEST_FAILED] userId={}, fileName={}", userId, fileName, e);
        }
    }

    /**
     * 检索：根据用户问题，从向量库中找最相关的文档片段。
     * @return 拼接好的参考文本，如果没有找到相关内容返回空字符串
     */
    public String retrieve(String query, String userId) {
        if (query == null || query.isBlank()) {
            return "";
        }
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .filterExpression("userId == '" + userId + "'")
                    .topK(TOP_K)
                    .build();
            List<Document> results = vectorStore.similaritySearch(request);
            if (results.isEmpty()) {
                return "";
            }
            String context = results.stream()
                    .map(Document::getText)
                    .collect(java.util.stream.Collectors.joining("\n---\n"));
            log.info("[RAG][RETRIEVE] userId={}, results={}, totalLength={}", userId, results.size(), context.length());
            return context;
        } catch (Exception e) {
            log.error("[RAG][RETRIEVE_FAILED] userId={}", userId, e);
            return "";
        }
    }

    /**
     * 删除用户的所有文档。
     */
    public void deleteByUser(String userId) {
        vectorStore.deleteByUserId(userId);
        log.info("[RAG][DELETE] userId={}", userId);
    }

    /**
     * 删除用户某个文件的文档。
     */
    public void deleteByFile(String userId, String fileName) {
        vectorStore.deleteByFile(userId, fileName);
        log.info("[RAG][DELETE_FILE] userId={}, fileName={}", userId, fileName);
    }

    // ==================== 切块逻辑 ====================

    /**
     * 把长文本切成多个 Document 块。
     * 策略：先按段落分，段落太长则按句子分，短段落合并，相邻块保留重叠。
     */
    private List<Document> splitIntoChunks(String content, String userId, String fileName, String docId) {
        List<String> paragraphs = splitParagraphs(content);
        List<String> mergedChunks = mergeSmallChunks(paragraphs, CHUNK_SIZE);
        List<String> overlappedChunks = addOverlap(mergedChunks, CHUNK_OVERLAP);

        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < overlappedChunks.size(); i++) {
            String chunkText = overlappedChunks.get(i).trim();
            if (chunkText.isEmpty()) continue;
            Map<String, Object> metadata = Map.of(
                    "docId", docId,
                    "userId", userId,
                    "fileName", fileName,
                    "chunkIndex", i);
            documents.add(new Document(chunkText, metadata));
        }
        return documents;
    }

    /** 按段落分割（双换行）。 */
    private List<String> splitParagraphs(String text) {
        String[] parts = text.split("\\n\\n+");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                // 如果单个段落超过 CHUNK_SIZE，按句子再分
                if (trimmed.length() > CHUNK_SIZE) {
                    result.addAll(splitBySentence(trimmed));
                } else {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    /** 按句号、问号、感叹号分割长段落。 */
    private List<String> splitBySentence(String text) {
        // 中英文标点分割，保留标点
        String[] sentences = text.split("(?<=[。！？.!?\\n])\\s*");
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (current.length() + sentence.length() > CHUNK_SIZE && !current.isEmpty()) {
                result.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            result.add(current.toString().trim());
        }
        return result;
    }

    /** 合并短段落，直到接近 maxSize。 */
    private List<String> mergeSmallChunks(List<String> chunks, int maxSize) {
        List<String> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String chunk : chunks) {
            if (buffer.length() + chunk.length() + 1 > maxSize && !buffer.isEmpty()) {
                merged.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n\n");
            }
            buffer.append(chunk);
        }
        if (!buffer.isEmpty()) {
            merged.add(buffer.toString().trim());
        }
        return merged;
    }

    /** 在相邻块之间添加重叠文本。 */
    private List<String> addOverlap(List<String> chunks, int overlap) {
        if (chunks.size() <= 1 || overlap <= 0) {
            return chunks;
        }
        List<String> result = new ArrayList<>();
        result.add(chunks.getFirst());
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String overlapText = prev.length() > overlap
                    ? prev.substring(prev.length() - overlap)
                    : prev;
            result.add(overlapText + chunks.get(i));
        }
        return result;
    }
}