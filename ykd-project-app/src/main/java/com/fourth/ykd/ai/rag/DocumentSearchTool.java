package com.fourth.ykd.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库检索工具 — Agentic RAG。
 *
 * <p>让 AI 在需要时主动调用本工具，从用户历史上传的文件和图片识别记忆
 * 中精确检索信息。与 BaiduSearchTool（互联网搜索）形成双通道检索体系：
 * <ul>
 *   <li>{@code search_user_knowledge} → 用户私有知识库（上传的文件/图片识别结果）</li>
 *   <li>{@code search_realtime_information} → 互联网公开信息（新闻/时事/动态）</li>
 * </ul>
 *
 * <p>使用场景示例：
 * <ul>
 *   <li>用户问"我的健身计划是什么" → 搜索健身计划.docx 的内容</li>
 *   <li>用户问"之前发的咖啡菜单有哪些" → 搜索咖啡.xlsx 的内容</li>
 *   <li>用户问"我上传的英语计划里写了什么" → 搜索英语计划.pdf 的内容</li>
 *   <li>用户模糊询问"我之前说过要做什么来着" → 检索所有相关记忆</li>
 * </ul>
 */
@Slf4j
@Component
public class DocumentSearchTool {

    private final SQLiteVectorStore vectorStore;

    /** 主动检索时使用的 TopK（比自动检索的 3 更大以扩大召回范围）。 */
    private static final int ACTIVE_SEARCH_TOP_K = 5;

    /** 主动检索时使用的相似度阈值（比自动检索的 0.3 略低以扩大召回范围）。 */
    private static final double ACTIVE_SEARCH_THRESHOLD = 0.25;

    public DocumentSearchTool(SQLiteVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Tool(name = "search_user_knowledge", description = """
            从用户私有知识库中检索信息。
            触发场景（用户的以下意图必须调用本工具）：
            - 用户询问"我的XXX文件""我上传的XXX""之前的XXX文档"等涉及已上传文件的问题
            - 用户询问图片相关内容（如"我发的那张图""之前生成的图片里有什么"）
            - 用户要求回顾、查找、搜索之前分享过的文件内容
            - 用户模糊询问"我之前说过XXX""你还记得XXX吗""之前聊过XXX"
            - 用户想查看具体文件中的数据（如"咖啡菜单里有什么""健身计划是什么"）
            本工具搜索用户个人知识库（已上传文件和图片识别结果），不搜索互联网。
            天气、新闻时事、数学计算、多语言翻译不得调用本工具。
            检索无结果时可更换更简短的关键词重试一次。
            """)
    public String search(
            @ToolParam(description = "搜索关键词或问题，例如'健身计划''咖啡菜单''英语学习安排''之前上传的文件'",
                    required = true)
            String query,
            @ToolParam(description = "当前对话的用户ID（conversationId），用于隔离不同用户的知识库",
                    required = true)
            String userId,
            @ToolParam(description = "返回结果数量，默认5条，范围3-10", required = false)
            Integer topK
    ) {
        if (query == null || query.trim().isEmpty()) {
            return "知识库检索失败：搜索关键词不能为空。";
        }
        if (userId == null || userId.isBlank()) {
            return "知识库检索失败：用户标识缺失，无法确定知识库范围。";
        }

        String normalizedQuery = query.trim();
        int resultCount = topK == null ? ACTIVE_SEARCH_TOP_K
                : Math.max(3, Math.min(topK, 10));

        log.info("[RAG][TOOL][SEARCH][START] query={}, userId={}, topK={}",
                summary(normalizedQuery, 50), summary(userId, 20), resultCount);

        List<Document> docs;
        try {
            docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(normalizedQuery)
                            .filterExpression(new Filter.Expression(
                                    Filter.ExpressionType.EQ,
                                    new Filter.Key("userId"),
                                    new Filter.Value(userId)
                            ))
                            .topK(resultCount)
                            .similarityThreshold(ACTIVE_SEARCH_THRESHOLD)
                            .build()
            );
        } catch (Exception e) {
            log.warn("[RAG][TOOL][SEARCH][FAILED] query={}, userId={}, reason={}",
                    summary(normalizedQuery, 30), userId, e.getMessage());
            return "知识库检索失败：检索服务暂时不可用，请稍后重试。";
        }

        if (docs == null || docs.isEmpty()) {
            log.info("[RAG][TOOL][NO_MATCH] query={}, userId={}",
                    summary(normalizedQuery, 30), userId);
            return String.format(
                    "用户知识库中未找到与\"%s\"相关的文档。建议尝试更短或更通用的关键词重新搜索。",
                    normalizedQuery.length() > 40
                            ? normalizedQuery.substring(0, 40) + "..."
                            : normalizedQuery);
        }

        log.info("[RAG][TOOL][SEARCH][SUCCESS] query={}, docsCount={}",
                summary(normalizedQuery, 30), docs.size());

        return formatResults(normalizedQuery, docs);
    }

    // ==================== 结果格式化 ====================

    /** 将检索结果格式化为结构化文本，供 AI 理解和引用。 */
    private String formatResults(String query, List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是从用户知识库中检索到关于\"").append(query).append("\"的结果：\n\n");

        int count = 0;
        for (Document doc : docs) {
            String fileName = (String) doc.getMetadata()
                    .getOrDefault("fileName", "未知来源");
            String text = doc.getText();
            if (text == null || text.isBlank()) continue;

            count++;
            String displayText = truncateToSentence(text, 500);
            Object scoreObj = doc.getMetadata().get("score");
            double score = scoreObj instanceof Number n ? n.doubleValue() : 0.0;

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

    /** 截断文本用于日志输出。 */
    private static String summary(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}