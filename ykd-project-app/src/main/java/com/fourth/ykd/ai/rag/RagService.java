package com.fourth.ykd.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * RAG 核心服务：文档切块、存入向量库、语义检索。
 *
 * <p>v2 增强：
 * <ul>
 *   <li>噪声过滤：≤5字纯寒暄跳过检索，节省 Embedding API 费用</li>
 *   <li>LRU 缓存：同 query+userId 在 TTL 内直接命中，避免重复检索</li>
 *   <li>结果后处理：过滤低信息量 chunk + 总长度压缩，防止挤压 system prompt</li>
 * </ul>
 */
@Slf4j
@Service
public class RagService {

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;
    private static final int TOP_K = 3;

    // ==================== 检索参数 ====================
    /** 缓存 TTL（分钟）。 */
    private static final int CACHE_TTL_MINUTES = 5;
    /** 缓存最大条目数。 */
    private static final int CACHE_MAX_SIZE = 100;
    /** 注入 context 的最大字符数，防止挤压 system prompt 中的工具规则。 */
    private static final int MAX_CONTEXT_CHARS = 2500;
    /** 视为低信息量的最小 chunk 长度。 */
    private static final int MIN_CHUNK_LENGTH = 20;

    private final SQLiteVectorStore vectorStore;

    // ==================== LRU 缓存 ====================

    /** 检索缓存：queryHash → CacheEntry（检索结果 + 存入时间戳）。 */
    private final Map<String, CacheEntry> queryCache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            });

    /** 缓存条目：封装检索结果和创建时间。 */
    private record CacheEntry(List<Document> documents, Instant cachedAt) {
        boolean isValid() {
            return cachedAt.plus(Duration.ofMinutes(CACHE_TTL_MINUTES)).isAfter(Instant.now());
        }
    }

    public RagService(SQLiteVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // ==================== 文档摄入 ====================

    /**
     * 存入文档：切块 → 向量化 → 存储。
     *
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

    // ==================== 语义检索（增强版） ====================

    /**
     * 检索：根据用户问题，从向量库中找最相关的文档片段。
     *
     * <p>v2 增强流程：
     * <ol>
     *   <li>输入校验</li>
     *   <li>噪声过滤 — 纯寒暄/命令类短消息跳过检索</li>
     *   <li>LRU 缓存查询 — 同 query 在 TTL 内直接返回</li>
     *   <li>向量检索 — 缓存未命中时执行</li>
     *   <li>结果后处理 — 过滤低信息量 chunk + 长度压缩</li>
     *   <li>写入缓存</li>
     * </ol>
     *
     * @param query  用户问题
     * @param userId 用户ID（用于多用户隔离）
     * @return 拼接好的参考文本，如果没有找到相关内容返回空字符串
     */
    public String retrieve(String query, String userId) {
        // Step 0: 输入校验
        if (query == null || query.isBlank() || userId == null || userId.isBlank()) {
            return "";
        }

        String normalizedQuery = query.trim();

        // Step 1: 噪声过滤 — 纯寒暄/命令类短消息跳过 RAG 检索，节省 Embedding API
        if (isNoise(normalizedQuery)) {
            log.info("[RAG][NOISE_FILTER] query='{}' 匹配噪声规则，跳过检索", normalizedQuery);
            return "";
        }

        // Step 2: LRU 缓存查询
        String cacheKey = hashQuery(normalizedQuery + "|" + userId);
        CacheEntry cached = queryCache.get(cacheKey);
        if (cached != null && cached.isValid()) {
            log.info("[RAG][CACHE][HIT] userId={}, query={}, docsCount={}",
                    userId, summary(normalizedQuery, 30), cached.documents().size());
            return formatContext(cached.documents());
        }

        // Step 3: 向量检索
        List<Document> results;
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(normalizedQuery)
                    .filterExpression(new Filter.Expression(
                            Filter.ExpressionType.EQ,
                            new Filter.Key("userId"),
                            new Filter.Value(userId)
                    ))
                    .topK(TOP_K)
                    .build();
            results = vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.error("[RAG][RETRIEVE_FAILED] userId={}, query={}", userId,
                    summary(normalizedQuery, 30), e);
            return "";
        }

        if (results.isEmpty()) {
            log.info("[RAG][NO_MATCH] userId={}, query={}", userId,
                    summary(normalizedQuery, 30));
            return "";
        }

        // Step 4: 结果后处理
        List<Document> processed = postProcessResults(results);

        // Step 5: 写入缓存（只有检索到结果时才缓存，避免缓存空结果）
        queryCache.put(cacheKey, new CacheEntry(new ArrayList<>(processed), Instant.now()));

        log.info("[RAG][RETRIEVE] userId={}, results={}, afterFilter={}, cacheSize={}",
                userId, results.size(), processed.size(), queryCache.size());

        return formatContext(processed);
    }

    // ==================== 噪声过滤器 ====================

    /**
     * 判断是否为噪声消息 — 不值得检索的纯寒暄/纯命令。
     *
     * <p>匹配规则：
     * <ul>
     *   <li>≤5 字的纯寒暄（你好/谢谢/好的/嗯/哦/哈哈/OK/知道了/明白了/再见/拜拜/早/晚安）</li>
     *   <li>不做判定的边界（如 6 字"好的谢谢啊"）→ 不过滤，宁可多搜不少搜</li>
     * </ul>
     */
    private static boolean isNoise(String query) {
        if (query.length() > 5) {
            return false;
        }
        // 包含中文/英文实质性内容的短消息（如"杭州天气"4字）→ 不过滤
        if (query.length() >= 3
                && !query.matches("^[你好谢谢嗯哦啊哈哈嘿拜再见晚安早OKok嘿多谢]+[!！。.]*$")) {
            return false;
        }
        return query.matches(
                "^(你好|您好|谢谢|多谢|好的|嗯|哦|哈哈|嘿嘿|OK|ok|Okay|知道了|明白了|再见|拜拜|早|晚安)[!！。.]*$");
    }

    // ==================== 结果后处理 ====================

    /**
     * 对检索结果做后处理：过滤低信息量 chunk + 长度压缩。
     *
     * <p>策略：
     * <ol>
     *   <li>过滤文本过短的低信息量 chunk（&lt; 20 字符）</li>
     *   <li>总字符数超过 MAX_CONTEXT_CHARS 时对每个 chunk 智能截断（句子边界优先）</li>
     * </ol>
     */
    private List<Document> postProcessResults(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }

        // Step 1: 过滤过短文段
        List<Document> filtered = docs.stream()
                .filter(doc -> doc.getText() != null && doc.getText().length() >= MIN_CHUNK_LENGTH)
                .toList();

        if (filtered.isEmpty()) {
            return docs; // 如果全被过滤了，保留原始结果
        }

        // Step 2: 总长度控制
        int totalChars = filtered.stream().mapToInt(doc -> doc.getText().length()).sum();
        if (totalChars <= MAX_CONTEXT_CHARS) {
            return filtered;
        }

        int perDocLimit = MAX_CONTEXT_CHARS / Math.max(filtered.size(), 1);
        return filtered.stream()
                .map(doc -> {
                    String text = doc.getText();
                    if (text.length() <= perDocLimit) {
                        return doc;
                    }
                    int cut = Math.min(perDocLimit, text.length());
                    // 尽量在句子边界截断
                    for (int i = cut - 1; i >= cut / 2; i--) {
                        char c = text.charAt(i);
                        if (c == '。' || c == '！' || c == '？' || c == '\n') {
                            cut = i + 1;
                            break;
                        }
                    }
                    return new Document(
                            text.substring(0, cut) + "\n...(内容已截断)",
                            doc.getMetadata()
                    );
                })
                .toList();
    }

    // ==================== 结果格式化 ====================

    /** 将检索结果拼接为带来源标记的上下文字符串。 */
    private String formatContext(List<Document> results) {
        return results.stream()
                .map(doc -> {
                    String fileName = (String) doc.getMetadata().getOrDefault("fileName", "未知来源");
                    return "【来源：" + fileName + "】\n" + doc.getText();
                })
                .collect(java.util.stream.Collectors.joining("\n\n---\n\n"));
    }

    // ==================== 删除操作 ====================

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

    // ==================== 缓存工具 ====================

    /** 对查询字符串生成 MD5 缓存键（取前 8 位 hex），避免 hashCode 碰撞。 */
    private static String hashQuery(String query) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(query.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().substring(0, 8);
        } catch (Exception e) {
            return String.valueOf(query.hashCode());
        }
    }

    /** 截断文本用于日志输出。 */
    private static String summary(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
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