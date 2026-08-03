package com.fourth.ykd.ai.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG Hook — 检索增强生成钩子。
 *
 * <p>对应 RAG 官方文档「使用 MessagesModelHook 实现两步RAG」模式。
 * 实现 BaseAdvisor（Spring AI 1.1.2 的 Hook/Interceptor 接口），
 * 在每次模型调用前（before）通过向量检索增强系统提示。
 *
 * 执行流程：
 *         从Prompt中提取最后用户消息+对话上下文
 *       ->跳过系统内部消息（如【系统指令】和【定时任务触发】）
 *       ->噪声过滤（纯寒暄/命令）
 *       ->查询改写（口语→正式检索表达式）
 *       ->先查LRU缓存，未命中则调VectorStore相似度检索
 *       ->结果后处理（去重+多样性重排序+长度压缩）
 *       ->将检索到的文档片段构建为结构化系统消息
 *       ->通过augmentSystemMessage注入到Prompt中
 *       ->检索失败时静默跳过，不阻断对话流程
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagHook implements BaseAdvisor {

    // 向量存储 Bean（使用 SimpleVectorStore 实现，底层是 DashScope Embedding）
    private final VectorStore ragVectorStore;
    // RAG配置对象：包含topK、相似度阈值、缓存TTL、文档路径等配置参数
    private final RagVectorStoreConfig config;
    // DeepSeek ChatModel，用于查询改写（口语→正式检索表达式）
    private final ChatModel chatModel;

    // 系统内部消息前缀，匹配时跳过RAG检索，避免浪费Embedding API调用
    private static final Pattern SYSTEM_INTERNAL =
            Pattern.compile("^【系统指令】|^【定时任务触发】");

    // 指代词检测：仅当当前问题包含指代词时才拼接对话上下文，避免AI历史回答污染检索查询
    // "那个地方""那个时候"等是独立表达，不作为指代词触发上下文拼接
    private static final Pattern PRONOUN_PATTERN =
            Pattern.compile("它|他|她|这个(?!地方|时候|东西|情况|问题)|那个(?!地方|时候|东西|情况|问题)|这些|那些|这里|那里|上面|上文|刚才|前面");

    //==================== 检索缓存 ====================
    // 缓存最大容量：超过100条时自动淘汰最久未访问的条目
    private static final int CACHE_MAX_SIZE = 100;

    /**
     * 检索缓存：queryHash → CacheEntry（检索结果 + 存入时间戳）
     *
     * 使用 access-order（访问顺序）LinkedHashMap 实现 LRU 淘汰策略：
     *     accessOrder=true：每次 get() 或 put() 都会将条目移到链表末尾
     *     链表头部的条目就是最久未访问的，会被自动淘汰
     */
    final Map<String, CacheEntry> queryCache =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            });
    // 缓存条目：封装检索结果和创建时间。
    record CacheEntry(List<Document> documents, Instant cachedAt) {
        // 判断缓存条目是否仍在有效期内。
        boolean isValid(Duration ttl) {
            return cachedAt.plus(ttl).isAfter(Instant.now());
        }
    }
    // 对查询字符串生成 MD5 缓存键（取前 8 位 hex），避免 hashCode 碰撞。
    static String hashQuery(String query) {
        String normalized = query.replaceAll("[？?！!。.，,、\\s]+", "");
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 查询增强 ====================

    /** 查询改写的 system prompt — 将口语转为检索友好的关键词短语。 */
    private static final String QUERY_AUGMENT_PROMPT = """
            你是查询改写助手。将用户的口语问题改写为适合文档检索的自然语言查询。
            严格规则：
            1. 删除语气词（呢、吧、啊、嘛）和礼貌用语（请问、帮我、能不能）
            2. 将代词替换为具体名词（"它""这个""那个"→根据上下文推断具体指什么）
            2.5 保留人名中的"小"字：如"小夏""小王""小张"是完整人名，"小"不是前缀，必须保留
            3. 口语转书面语：
               "咋搞/怎么弄/咋办"→"如何操作/配置/排查"
               "Key"→"API密钥"，"DB"→"数据库"
            4. 扩展缩写并补全关键词（"部署"→"部署说明 环境要求"）
            5. 输出一句简洁的自然语言问题，保持完整的主谓宾句式
               正确示例："小夏毕业于哪所大学""部署需要哪些环境变量"
               错误示例："夏 毕业院校 大学""部署 环境变量"（丢失人名/关键词堆砌）
            6. 最多输出 80 个字符
            """;

    /**
     * 轻量改写用户查询，提升向量检索召回率。
     *
     * <p>使用 DeepSeek 将口语化查询转写为正式检索表达式。
     * 改写失败时静默回退到原始查询，不阻断检索流程。
     */
    private String augmentQuery(String originalQuery) {
        try {
            String augmented = chatModel.call(
                    new Prompt(List.of(
                            new SystemMessage(QUERY_AUGMENT_PROMPT),
                            new UserMessage(originalQuery)
                    ))
            ).getResult().getOutput().getText();

            if (augmented != null && !augmented.isBlank() && augmented.length() <= 200) {
                String cleaned = augmented.trim();
                // 过滤明显非检索词的输出（如"好的""嗯"等模型误输出）
                if (cleaned.length() < 3 || cleaned.matches("^[好的嗯哦对行可]+[!！。.]*$")) {
                    log.info("[RAG][HOOK][AUGMENT][SKIP] 改写结果质量不足"
                            + "('{}')，回退到原始查询", cleaned);
                    return originalQuery;
                }
                log.info("[RAG][HOOK][AUGMENT] original='{}' → augmented='{}'",
                        summary(originalQuery, 40), summary(cleaned, 60));
                return cleaned;
            }
        } catch (Exception e) {
            log.debug("[RAG][HOOK][AUGMENT_FAILED] reason={}, 回退到原始查询",
                    e.getMessage());
        }
        return originalQuery;
    }

    // ==================== 检索结果后处理 ====================

    /** 上下文注入的最大字符数（防止挤压 system prompt 中的工具规则）。 */
    private static final int MAX_CONTEXT_CHARS = 2500;
    /** 每个文档来源最多保留的 chunk 数（保证来源多样性，4 以覆盖多段落文档）。 */
    private static final int MAX_CHUNKS_PER_SOURCE = 4;

    /**
     * 对检索结果做后处理：去重 + 多样性重排序 + 长度压缩。
     *
     * <p>处理策略：
     * <ol>
     *   <li>每个文档来源只保留相似度最高的 2 个 chunk</li>
     *   <li>过滤掉文本过短的低信息量 chunk（&lt; 30 字符）</li>
     *   <li>总长度超过阈值时对每个 chunk 智能截断</li>
     * </ol>
     */
    private List<Document> postProcessResults(List<Document> docs) {
        if (docs == null || docs.size() <= 1) {
            return docs == null ? List.of() : docs;
        }

        // Step 1: 按文档来源分组，每组只保留 top-2
        Map<String, List<Document>> bySource = docs.stream()
                .collect(Collectors.groupingBy(
                        doc -> (String) doc.getMetadata()
                                .getOrDefault("file_name", "unknown"),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<Document> diverse = new ArrayList<>();
        for (List<Document> group : bySource.values()) {
            // 组内按相似度降序，只取 top-2，并过滤过短 chunk
            group.stream()
                    .sorted((a, b) -> Double.compare(
                            getSimilarityScore(b), getSimilarityScore(a)))
                    .limit(MAX_CHUNKS_PER_SOURCE)
                    .filter(doc -> doc.getText() != null && doc.getText().length() >= 30)
                    .forEach(diverse::add);
        }

        if (diverse.isEmpty() && !docs.isEmpty()) {
            diverse.add(docs.get(0));
        }

        // Step 2: 总字符数超阈值则压缩
        int totalChars = diverse.stream()
                .mapToInt(doc -> doc.getText().length())
                .sum();

        if (totalChars > MAX_CONTEXT_CHARS) {
            int perDocLimit = MAX_CONTEXT_CHARS / Math.max(diverse.size(), 1);
            return diverse.stream()
                    .map(doc -> {
                        String text = doc.getText();
                        if (text.length() <= perDocLimit) return doc;
                        int cut = Math.min(perDocLimit, text.length());
                        while (cut > 0 && !Character.isWhitespace(text.charAt(cut - 1))
                                && text.charAt(cut - 1) != '\n') {
                            cut--;
                        }
                        if (cut < perDocLimit / 2) cut = perDocLimit;
                        String truncated = text.substring(0, cut)
                                + "\n...(内容已截断)";
                        return Document.builder()
                                .text(truncated)
                                .metadata(doc.getMetadata())
                                .build();
                    })
                    .toList();
        }

        return diverse;
    }

    /** 从文档元数据中安全提取相似度分数。 */
    private static double getSimilarityScore(Document doc) {
        Object score = doc.getMetadata().get("similarity");
        if (score instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    // ==================== Advisor 接口实现 ====================
    /**返回 Advisor 的唯一名称，用于日志标识和 Advisor 查找。*/
    @Override
    public String getName() {
        return "rag_hook";
    }
    /**返回 Advisor 在调用链中的执行顺序。*/
    @Override
    public int getOrder() {
        return 5;
    }

    /**
     * 模型调用前的钩子方法 — RAG 检索核心逻辑。
     * ChatClient每次调用.call()时，Advisor链中的所有Advisor.before()会按order升序依次执行。
     * 本方法执行完整的RAG检索流程：
     *      ->校验 Prompt 非空
     *      ->提取用户查询+对话上下文
     *      ->跳过系统内部消息（节省Embedding API）
     *      ->噪声过滤（纯寒暄/命令）
     *      ->查询改写（口语→正式检索表达式）
     *      ->带缓存的向量检索
     *      ->结果后处理（去重+多样性+压缩）
     *      ->无匹配时静默跳过
     *      ->将检索结果构建为结构化系统消息
     *      ->将检索上下文注入到Prompt的SystemMessage中
     *      ->用增强后的Prompt替换原Prompt
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Prompt prompt = request.prompt();
        if (prompt == null) {
            return request;
        }

        // Step 1: 提取用户查询（含对话上下文）
        String userQuery = extractUserQuery(prompt);
        if (userQuery == null || userQuery.isBlank()) {
            return request;
        }

        // 噪声过滤：纯寒暄/纯命令类短消息跳过RAG检索
        if (userQuery.length() <= 5
                && userQuery.matches("^(你好|谢谢|好的|嗯|哦|哈哈|[Oo][Kk]|知道了|明白了|再见|拜拜|早|晚安)[!！。.]*$")) {
            return request;
        }

        log.info("[RAG][HOOK] query={}", summary(userQuery, 50));

        // Step 2: 查询改写 + 向量检索（带缓存）
        // 将口语化查询（含对话上下文）改写为正式检索表达式
        String augmentedQuery = augmentQuery(userQuery);
        List<Document> retrievedDocs = retrieveWithCache(augmentedQuery);

        // Step 3: 无匹配时跳过
        if (retrievedDocs.isEmpty()) {
            log.info("[RAG][HOOK][NO_MATCH] query={}", summary(userQuery, 30));
            // 注入提示：自动检索无结果，引导 AI 主动调用 search_knowledge_base
            String noMatchHint = """

                    ═══ 知识库检索结果 ═══
                    自动检索未找到与当前问题直接匹配的文档。如果用户问题可能涉及
                    本系统知识库中的内容（包括用户信息、系统功能、配置说明等），
                    请调用 search_knowledge_base 工具尝试检索，不要直接回答
                    "没有相关信息"或凭训练数据猜测。
                    ═══ 检索结果结束 ═══
                    """;
            Prompt hintedPrompt = prompt.augmentSystemMessage(noMatchHint);
            return ChatClientRequest.builder()
                    .prompt(hintedPrompt)
                    .context(request.context())
                    .build();
        }

        // Step 4: 结果后处理 + 构建检索上下文
        List<Document> processedDocs = postProcessResults(retrievedDocs);
        String contextText = processedDocs.stream()
                .map(doc -> {
                    String fileName = (String) doc.getMetadata()
                            .getOrDefault("file_name", "未知来源");
                    return "【来源：" + fileName + "】\n" + doc.getText();
                })
                .collect(Collectors.joining("\n\n---\n\n"));

        // Step 5: 增强 SystemMessage
        String ragContext = """

                ═══ 知识库检索结果 ═══
                以下是从本系统官方文档中检索到的内容。这些内容是
                关于本系统的权威事实，你必须直接引用其中的具体信息
                （包括数字、路径、名称、配置值）来回答用户问题。
                如果以下内容不足以回答，请如实说明"知识库中暂无相关信息"，
                不要编造信息，也不要用"无法查看""属于后台配置"来回避。

                %s
                ═══ 检索结果结束 ═══
                """.formatted(contextText);
        Prompt enhancedPrompt = prompt.augmentSystemMessage(ragContext);
        // 记录增强日志
        List<String> docNames = processedDocs.stream()
                .map(doc -> (String) doc.getMetadata().getOrDefault("file_name", "?"))
                .distinct()
                .toList();
        String scores = processedDocs.stream()
                .map(d -> {
                    Object score = d.getMetadata().get("similarity");
                    return score != null ? String.format("%.3f", score) : "-";
                })
                .collect(Collectors.joining(","));
        log.info("[RAG][HOOK][ENHANCED] query={}, docsFound={}, scores=[{}], sources={}",
                summary(userQuery, 30), processedDocs.size(), scores, docNames);

        return ChatClientRequest.builder()
                .prompt(enhancedPrompt)
                .context(request.context())
                .build();
    }
    /**模型调用后的钩子方法 — 当前不做任何后处理。*/
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    // ==================== 内部方法 ====================

    /** 对话上下文窗口大小（最近 N 轮对话）。 */
    private static final int CONTEXT_WINDOW = 3;

    /**
     * 从 Prompt 中提取最后一条用户消息文本。
     * 仅当最后一条消息含指代词时才拼接最近几轮对话作为上下文。
     */
    private String extractUserQuery(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        if (messages == null || messages.isEmpty()) return null;

        // Step 1: 找到最后一条有效的 UserMessage
        String lastUserText = null;
        int userMsgIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage msg) {
                String text = msg.getText();
                if (SYSTEM_INTERNAL.matcher(text).find()) {
                    return null;
                }
                lastUserText = text;
                userMsgIndex = i;
                break;
            }
        }
        if (lastUserText == null) return null;

        // Step 2: 仅当当前问题包含指代词时才拼接上下文
        if (!PRONOUN_PATTERN.matcher(lastUserText).find()) {
            return lastUserText;
        }

        // Step 3: 收集对话上下文（仅在需要时）
        StringBuilder ctx = new StringBuilder();
        int collected = 0;
        for (int i = userMsgIndex - 1; i >= 0 && collected < CONTEXT_WINDOW; i--) {
            Message msg = messages.get(i);
            if (msg instanceof AssistantMessage am) {
                ctx.insert(0, "AI回答: " + summary(am.getText(), 80) + "\n");
                collected++;
            } else if (msg instanceof UserMessage um) {
                String t = um.getText();
                if (!SYSTEM_INTERNAL.matcher(t).find()) {
                    ctx.insert(0, "用户问: " + summary(t, 80) + "\n");
                }
            }
        }

        if (!ctx.isEmpty()) {
            return ctx + "当前问题: " + lastUserText;
        }
        return lastUserText;
    }

    /** 带缓存的向量检索：先查 LRU 缓存，未命中则调 VectorStore 执行相似度检索。 */
    private List<Document> retrieveWithCache(String userQuery) {
        String cacheKey = hashQuery(userQuery);
        Duration cacheTtl = Duration.ofMinutes(config.getCacheTtlMinutes());

        CacheEntry cached = queryCache.get(cacheKey);
        if (cached != null && cached.isValid(cacheTtl)) {
            log.info("[RAG][CACHE][HIT] query={}, docsCount={}",
                    summary(userQuery, 30), cached.documents().size());
            return cached.documents();
        }
        List<Document> docs;
        try {
            docs = ragVectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(userQuery)
                            .topK(config.getTopK())
                            .similarityThreshold(config.getSimilarityThreshold())
                            .build()
            );
        } catch (Exception e) {
            log.warn("[RAG][HOOK][RETRIEVAL_FAILED] query={}, reason={}",
                    summary(userQuery, 30), e.getMessage());
            return List.of();
        }

        // 只有检索到结果时才缓存，避免缓存空结果导致后续永远搜不到
        if (!docs.isEmpty()) {
            queryCache.put(cacheKey, new CacheEntry(docs, Instant.now()));
        }
        log.info("[RAG][CACHE][MISS] query={}, docsCount={}, cacheSize={}",
                summary(userQuery, 30), docs.size(), queryCache.size());
        return docs;
    }

    /**
     * 从缓存中获取文档（不触发检索）。
     *
     * <p>供同包类复用检索缓存，避免重复消耗 Embedding API。
     *
     * @param query 用户查询字符串
     * @return 缓存的文档列表，缓存未命中或过期时返回 null
     */
    List<Document> getCachedDocuments(String query) {
        String cacheKey = hashQuery(query);
        CacheEntry cached = queryCache.get(cacheKey);
        if (cached != null && cached.isValid(
                Duration.ofMinutes(config.getCacheTtlMinutes()))) {
            return cached.documents();
        }
        return null;
    }

    /**截断文本用于日志输出，防止日志过长。*/
    private static String summary(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}