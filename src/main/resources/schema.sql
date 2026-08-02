-- 保存完整微信聊天历史，用于审计、页面展示和短期 ChatMemory 重启恢复。
CREATE TABLE IF NOT EXISTS chat_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TEXT,
    CONSTRAINT ck_chat_message_role
        CHECK (role IN ('USER', 'ASSISTANT'))
);

-- 加速按用户查询最近未软删除消息。
CREATE INDEX IF NOT EXISTS idx_chat_message_conversation_active
    ON chat_message (conversation_id, deleted_at, id);

-- 保存经过 AI 提取和语义合并后的结构化长期记忆，SQLite 是该数据的事实源。
CREATE TABLE IF NOT EXISTS agent_memory (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    memory_type TEXT NOT NULL,
    memory_key TEXT,
    content TEXT NOT NULL,
    summary TEXT NOT NULL,
    importance REAL NOT NULL DEFAULT 0.5,
    confidence REAL NOT NULL DEFAULT 0.5,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    source_conversation_id TEXT,
    content_hash TEXT NOT NULL,
    supersedes_id TEXT,
    expires_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_accessed_at DATETIME,
    access_count INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (supersedes_id) REFERENCES agent_memory(id),
    CHECK (importance >= 0.0 AND importance <= 1.0),
    CHECK (confidence >= 0.0 AND confidence <= 1.0),
    CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'DELETED')),
    CHECK (
        memory_type IN (
            'PROFILE',
            'PREFERENCE',
            'PROJECT',
            'TASK',
            'EPISODE',
            'ARTIFACT'
        )
    )
);

-- 以下索引分别服务过期过滤、内容哈希去重、稳定 key 查询和用户隔离查询。
CREATE INDEX IF NOT EXISTS idx_agent_memory_expiration
    ON agent_memory (expires_at);

CREATE INDEX IF NOT EXISTS idx_agent_memory_user_hash
    ON agent_memory (user_id, content_hash);

CREATE INDEX IF NOT EXISTS idx_agent_memory_user_key_status
    ON agent_memory (user_id, memory_key, status);

CREATE INDEX IF NOT EXISTS idx_agent_memory_user_status
    ON agent_memory (user_id, status);

CREATE INDEX IF NOT EXISTS idx_agent_memory_user_type_status
    ON agent_memory (user_id, memory_type, status);
-- Persists Redis vector-index synchronization tasks created in SQLite transactions.
-- Failed tasks remain pending for asynchronous retry without changing SQLite facts.
CREATE TABLE IF NOT EXISTS memory_index_outbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    memory_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME,
    CHECK (operation IN ('UPSERT', 'DELETE')),
    CHECK (status IN ('PENDING', 'DONE')),
    CHECK (retry_count >= 0)
);

-- Supports due-task filtering and ordering in the background scanner.
CREATE INDEX IF NOT EXISTS idx_memory_index_outbox_due
    ON memory_index_outbox (status, next_attempt_at, id);
