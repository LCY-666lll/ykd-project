package com.fourth.ykd.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于 SQLite 的持久化向量存储。
 * 向量以 BLOB 形式存储在 document_chunk 表中，相似度计算在内存中完成。
 */
@Slf4j
public class SQLiteVectorStore implements VectorStore {

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;

    public SQLiteVectorStore(EmbeddingModel embeddingModel, JdbcTemplate jdbcTemplate) {
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        initTable();
    }

    /** 建表（如不存在）。 */
    private void initTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS document_chunk (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    doc_id      TEXT NOT NULL,
                    user_id     TEXT NOT NULL,
                    file_name   TEXT,
                    content     TEXT NOT NULL,
                    embedding   BLOB NOT NULL,
                    chunk_index INTEGER DEFAULT 0,
                    created_at  TEXT DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_doc_chunk_user ON document_chunk(user_id)");
        log.info("[RAG][SQLiteVectorStore] document_chunk 表初始化完成");
    }

    @Override
    public void add(List<Document> documents) {
        if (documents.isEmpty()) {
            return;
        }
        // 批量获取 embedding
        List<String> texts = documents.stream().map(Document::getText).toList();
        EmbeddingRequest request = new EmbeddingRequest(texts, null);
        EmbeddingResponse response = embeddingModel.call(request);
        List<float[]> embeddings = response.getResults().stream()
                .map(Embedding::getOutput)
                .toList();

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            float[] vector = embeddings.get(i);
            Map<String, Object> meta = doc.getMetadata();
            String docId = String.valueOf(meta.getOrDefault("docId", UUID.randomUUID().toString()));
            String userId = String.valueOf(meta.getOrDefault("userId", "unknown"));
            String fileName = String.valueOf(meta.getOrDefault("fileName", ""));
            int chunkIndex = Integer.parseInt(String.valueOf(meta.getOrDefault("chunkIndex", 0)));

            jdbcTemplate.update(
                    "INSERT INTO document_chunk (doc_id, user_id, file_name, content, embedding, chunk_index) VALUES (?, ?, ?, ?, ?, ?)",
                    docId, userId, fileName, doc.getText(), floatArrayToBytes(vector), chunkIndex);
        }
        log.info("[RAG][SQLiteVectorStore] 存入 {} 个文档块", documents.size());
    }

    @Override
    //    1.问题文本生成查询向量
    //    2.解析Filter表达式，提取userId，实现用户知识库隔离
    //    3.从数据库查出该用户全部向量切片
    //    4.内存循环计算余弦相似度，排序，过滤阈值，返回topK
    public List<Document> similaritySearch(SearchRequest request) {
        String query = request.getQuery();
        int topK = request.getTopK();

        // 把查询文本转向量
        EmbeddingRequest embedReq = new EmbeddingRequest(List.of(query), null);
        EmbeddingResponse embedResp = embeddingModel.call(embedReq);
        float[] queryVector = embedResp.getResults().getFirst().getOutput();

        // 从 filterExpression 中提取 userId 过滤条件
        String userIdFilter = extractUserIdFromFilter(request.getFilterExpression());

        // 查询所有候选行
        List<ChunkRow> rows;
        if (userIdFilter != null) {
            rows = jdbcTemplate.query(
                    "SELECT doc_id, user_id, file_name, content, embedding, chunk_index FROM document_chunk WHERE user_id = ?",
                    this::mapRow, userIdFilter);
        } else {
            rows = jdbcTemplate.query(
                    "SELECT doc_id, user_id, file_name, content, embedding, chunk_index FROM document_chunk",
                    this::mapRow);
        }

        // 计算余弦相似度，取 topK
        return rows.stream()
                .map(row -> {
                    float similarity = cosineSimilarity(queryVector, bytesToFloatArray(row.embedding()));
                    return new ScoredChunk(row, similarity);
                })
                .sorted((a, b) -> Float.compare(b.score(), a.score()))
                .limit(topK)
                .filter(sc -> sc.score() > 0.3f) // 相似度阈值
                .map(sc -> new Document(sc.row().content(), Map.of(
                        "docId", sc.row().docId(),
                        "userId", sc.row().userId(),
                        "fileName", sc.row().fileName(),
                        "chunkIndex", String.valueOf(sc.row().chunkIndex()),
                        "score", String.valueOf(sc.score()))))
                .collect(Collectors.toList());
    }
//    四个删除方法的区别
//
//            ┌────────────────────────────────┬────────────────────────────┬──────────────────────────────────────────────────────────────────────────┐
//            │              方法               │         删除范围             │                                 使用场景                                  │
//            ├────────────────────────────────┼────────────────────────────┼──────────────────────────────────────────────────────────────────────────┤
//            │ delete(List<String> idList)    │ 删除指定 docId 的文档块       │ 删除单个文件的所有切片（一个文件被拆成多个 chunk，它们共享同一个 docId）              │
//            ├────────────────────────────────┼────────────────────────────┼──────────────────────────────────────────────────────────────────────────┤
//            │ delete(Filter.Expression)      │ 删除过滤表达式匹配的文档块       │ Spring AI 框架调用，内部提取 userId 执行删除                                  │
//            ├────────────────────────────────┼────────────────────────────┼──────────────────────────────────────────────────────────────────────────┤
//            │ deleteByUserId(String)         │ 删除该用户全部文档块            │ 清空用户整个知识库                                                          │
//            ├────────────────────────────────┼────────────────────────────┼──────────────────────────────────────────────────────────────────────────┤
//            │ deleteByFile(userId, fileName) │ 删除该用户指定文件的文档块       │ 删除用户某个特定文件                                                         │
//            └────────────────────────────────┴────────────────────────────┴──────────────────────────────────────────────────────────────────────────┘


    @Override
    //根据 docId 删除，删除单个文件全部切片
    public void delete(List<String> idList) {
        if (idList == null || idList.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(idList.size(), "?"));
        jdbcTemplate.update("DELETE FROM document_chunk WHERE doc_id IN (" + placeholders + ")", idList.toArray());
    }

    @Override//删除过滤表达式匹配的文档块
    public void delete(Filter.Expression filterExpression) {
        // 从 Filter.Expression 中提取 userId 并删除
        String userId = extractUserIdFromFilter(filterExpression);
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM document_chunk WHERE user_id = ?", userId);
            log.info("[RAG][SQLiteVectorStore] 按 filterExpression 删除 userId={}", userId);
        }
    }

    /** 根据 userId 删除该用户的所有文档块。 */
    public void deleteByUserId(String userId) {
        jdbcTemplate.update("DELETE FROM document_chunk WHERE user_id = ?", userId);
    }

    /** 根据 userId + fileName 删除特定文件的文档块。 */
    public void deleteByFile(String userId, String fileName) {
        jdbcTemplate.update("DELETE FROM document_chunk WHERE user_id = ? AND file_name = ?", userId, fileName);
    }

    // ==================== 内部方法 ====================

    /**
     * 从 Filter.Expression 中提取 userId 的值。
     * 支持格式: EQ(userId, 'xxx')
     */
    private String extractUserIdFromFilter(Filter.Expression expression) {
        if (expression == null) {
            return null;
        }
        // 递归查找包含 "userId" key 的 EQ 表达式
        if (expression.type() == Filter.ExpressionType.EQ
                && expression.left() instanceof Filter.Key key
                && "userId".equals(key.key())
                && expression.right() instanceof Filter.Value value) {
            return String.valueOf(value.value());
        }
        // 递归检查 AND/OR 子表达式
        if (expression.left() instanceof Filter.Expression leftExpr) {
            String result = extractUserIdFromFilter(leftExpr);
            if (result != null) return result;
        }
        if (expression.right() instanceof Filter.Expression rightExpr) {
            return extractUserIdFromFilter(rightExpr);
        }
        return null;
    }
// JDBC 行映射器，将数据库行转为 ChunkRow 记录
    private ChunkRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ChunkRow(
                rs.getString("doc_id"),
                rs.getString("user_id"),
                rs.getString("file_name"),
                rs.getString("content"),
                rs.getBytes("embedding"),
                rs.getInt("chunk_index"));
    }

    /** 余弦相似度。 */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0f;
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denom = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denom == 0f ? 0f : dot / denom;
    }
// float 数组 → byte 数组（用于存储向量）
    private byte[] floatArrayToBytes(float[] floats) {
        ByteBuffer buf = ByteBuffer.allocate(floats.length * 4);
        for (float f : floats) {
            buf.putFloat(f);
        }
        return buf.array();
    }
// byte 数组 → float 数组（用于读取向量）
    private float[] bytesToFloatArray(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buf.getFloat();
        }
        return floats;
    }

    private record ChunkRow(String docId, String userId, String fileName, String content, byte[] embedding, int chunkIndex) {}

    private record ScoredChunk(ChunkRow row, float score) {}
}