package com.fourth.ykd.ai.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 知识库检索工具 — Agentic RAG。
 *
 * <p>让 DeepSeek 在需要时主动调用本工具，从私有知识库文档中精确检索信息。
 * 与 BaiduSearchTool（互联网搜索）形成双通道检索体系：
 * <ul>
 *   <li>{@code search_knowledge_base} → 本系统知识库（功能介绍/API文档/FAQ/配置/部署）</li>
 *   <li>{@code search_realtime_information} → 互联网公开信息（新闻/时事/动态）</li>
 * </ul>
 *
 * <p>缓存复用：本类与 RagHook 同包，直接复用其 LRU 检索缓存，
 * 避免相同查询重复消耗 Embedding API。
 *
 * @see RagHook 两步 RAG 自动检索钩子
 * @see com.fourth.ykd.ai.utils.BaiduSearchTool 百度互联网搜索工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentSearchTool {

    private final VectorStore ragVectorStore;

    private final RagVectorStoreConfig config;

    private final RagHook ragHook;

    /** 主动检索时使用的相似度阈值（比自动检索的0.55略低以扩大召回范围）。 */
    private static final double ACTIVE_SEARCH_THRESHOLD = 0.45;

    @Tool(name = "search_knowledge_base", description = """
            从本系统私有知识库中检索信息。
            触发场景（用户的以下意图必须调用本工具）：
            - 询问系统功能、配置、部署、API、FAQ、技术架构、故障排查
            - 询问知识库中可能存在的任何信息（如用户资料、项目文档、内部资料）
            - 你无法确定答案、或训练数据中没有相关信息时，优先检索知识库
            - 不要直接说"没有相关信息"，先检索知识库再判断
            本工具搜索本地知识库文档，不搜索互联网。
            天气、新闻时事、数学计算、多语言翻译不得调用本工具。
            检索无结果时可更换更简短的关键词重试一次。
            """)
    public String search(
            @ToolParam(description = "搜索关键词或问题，例如'部署说明''如何配置API Key''支持哪些文档格式'",
                    required = true)
            String query,
            @ToolParam(description = "返回结果数量，默认5条，范围3-10", required = false)
            Integer topK
    ) {
        if (query == null || query.trim().isEmpty()) {
            return "知识库检索失败：搜索关键词不能为空。";
        }

        String normalizedQuery = query.trim();
        int resultCount = topK == null
                ? Math.min(config.getTopK(), 7)
                : Math.max(3, Math.min(topK, 10));

        log.info("[RAG][TOOL][SEARCH][START] query={}, topK={}",
                summary(normalizedQuery, 50), resultCount);

        // 先查 RagHook 共享缓存，避免重复 Embedding API 调用
        List<Document> cached = ragHook.getCachedDocuments(normalizedQuery);
        if (cached != null && !cached.isEmpty()) {
            log.info("[RAG][TOOL][CACHE][HIT] query={}, docsCount={}",
                    summary(normalizedQuery, 30), cached.size());
            return formatResults(normalizedQuery, cached);
        }

        // 缓存未命中 → 执行向量检索
        List<Document> docs;
        try {
            docs = ragVectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(normalizedQuery)
                            .topK(resultCount)
                            .similarityThreshold(ACTIVE_SEARCH_THRESHOLD)
                            .build()
            );
        } catch (Exception e) {
            log.warn("[RAG][TOOL][SEARCH][FAILED] query={}, reason={}",
                    summary(normalizedQuery, 30), e.getMessage());
            return "知识库检索失败：检索服务暂时不可用，请稍后重试。";
        }

        if (docs == null || docs.isEmpty()) {
            log.info("[RAG][TOOL][NO_MATCH] query={}", summary(normalizedQuery, 30));
            return String.format(
                    "知识库中未找到与\"%s\"相关的文档。建议尝试更短或更通用的关键词重新搜索。",
                    normalizedQuery.length() > 40
                            ? normalizedQuery.substring(0, 40) + "..."
                            : normalizedQuery);
        }

        // 写入共享缓存（下次 RagHook 或本工具重复查询时直接命中）
        ragHook.queryCache.put(
                RagHook.hashQuery(normalizedQuery),
                new RagHook.CacheEntry(docs, Instant.now())
        );

        log.info("[RAG][TOOL][SEARCH][SUCCESS] query={}, docsCount={}, cacheSize={}",
                summary(normalizedQuery, 30), docs.size(), ragHook.queryCache.size());

        return formatResults(normalizedQuery, docs);
    }

    // ==================== 结果格式化 ====================

    /** 将检索结果格式化为结构化文本，供 DeepSeek 理解和引用。 */
    private String formatResults(String query, List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是从系统知识库中检索到关于\"").append(query).append("\"的结果：\n\n");

        int count = 0;
        for (Document doc : docs) {
            String fileName = (String) doc.getMetadata()
                    .getOrDefault("file_name", "未知来源");
            String text = doc.getText();
            if (text == null || text.isBlank()) continue;

            count++;
            String displayText = truncateToSentence(text, 500);
            double score = getScore(doc);

            sb.append(count).append(". **来源：").append(fileName)
                    .append("**（相似度：").append(String.format("%.1f%%", score * 100)).append("）\n");
            sb.append("   ").append(displayText.replace("\n", "\n   ")).append("\n\n");
        }

        sb.append("---\n共检索到 ").append(count).append(" 条结果。");
        sb.append("请基于以上内容回答用户问题，引用具体信息。");
        sb.append("如果检索结果不足以回答，请如实说明\"知识库中暂无相关信息\"，不要编造。");

        return sb.toString();
    }

    /** 截断文本到指定长度，优先在句子边界处截断。 */
    private static String truncateToSentence(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text == null ? "" : text;
        int cut = maxLen;
        for (int i = maxLen - 1; i >= maxLen / 2; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n') {
                cut = i + 1;
                break;
            }
        }
        return text.substring(0, cut) + "\n...(内容已截断)";
    }

    private static double getScore(Document doc) {
        Object score = doc.getMetadata().get("similarity");
        if (score instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private static String summary(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
