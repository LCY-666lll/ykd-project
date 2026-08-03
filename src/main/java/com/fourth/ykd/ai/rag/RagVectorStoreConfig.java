package com.fourth.ykd.ai.rag;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * RAG 向量存储配置。
 *
 * <p>创建 VectorStore Bean。
 * EmbeddingModel 由 spring-ai-alibaba-starter-dashscope 自动配置注入。
 *
 * <p>开发环境：SimpleVectorStore（内存，Spring AI 内置）→ 文件持久化
 * 生产切换：替换为 MilvusVectorStore / RedisVectorStore Bean
 */
@Slf4j
@Getter
@Configuration
public class RagVectorStoreConfig {

    @Value("${rag.retrieval.top-k}")
    private int topK;

    @Value("${rag.retrieval.similarity-threshold}")
    private double similarityThreshold;

    @Value("${rag.document.base-path}")
    private String documentBasePath;

    @Value("${rag.document.chunk-size}")
    private int chunkSize;

    @Value("${rag.document.max-text-length}")
    private int maxTextLength;

    @Value("${rag.cache.ttl-minutes}")
    private int cacheTtlMinutes;

    @Value("${rag.vector.persist-path}")
    private String persistPath;

    @Value("${rag.document.force-rebuild}")
    private boolean forceRebuild;

    @Value("${rag.config.version}")
    private int configVersion;

    @Value("${rag.interceptor.max-input-length}")
    private int maxInputLength;

    /**
     * 向量存储 Bean。
     *
     * <p>SimpleVectorStore 底层使用内存 Map 存储向量，适合开发和小规模知识库（&lt;1万文档块）。
     * 通过 save/load 实现文件持久化，重启不丢失。
     */
    @Bean
    public VectorStore ragVectorStore(
            @Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {

        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        Path persistFile = Path.of(persistPath);

        if (forceRebuild) {
            log.info("[RAG] forceRebuild=true，将跳过已有向量数据加载，启动后全量重建");
        } else if (Files.exists(persistFile)) {
            try {
                store.load(persistFile.toFile());
                log.info("[RAG] VectorStore 从文件恢复成功, path={}",
                        persistFile.toAbsolutePath());
            } catch (Exception e) {
                log.warn("[RAG] VectorStore 文件恢复失败，将初始化为空存储, path={}, reason={}",
                        persistFile.toAbsolutePath(), e.getMessage());
            }
        } else {
            log.info("[RAG] VectorStore 初始化完成（空存储）, embedding={}, persistPath={}",
                    embeddingModel.getClass().getSimpleName(), persistFile.toAbsolutePath());
        }

        return store;
    }

    /**
     * 将当前向量存储持久化到文件。
     *
     * <p>由 RagKnowledgeBuilder 在批量摄入完成后调用。
     */
    public void persistVectorStore(VectorStore vectorStore) {
        Path persistFile = Path.of(persistPath);
        try {
            Files.createDirectories(persistFile.getParent());
            ((SimpleVectorStore) vectorStore).save(persistFile.toFile());
            log.info("[RAG] VectorStore 持久化完成, path={}",
                    persistFile.toAbsolutePath());
        } catch (IOException e) {
            log.error("[RAG] VectorStore 持久化失败, path={}, reason={}",
                    persistFile.toAbsolutePath(), e.getMessage());
        }
    }
}
