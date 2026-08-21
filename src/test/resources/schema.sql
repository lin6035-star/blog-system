-- 多测试类共享 H2 内存库（DB_CLOSE_DELAY=-1），每个 Spring 上下文启动都会执行本文件。
-- 因此所有语句必须幂等：不 DROP（会删掉其他上下文的表），CREATE 用 IF NOT EXISTS。
CREATE TABLE IF NOT EXISTS articles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT,
    author_id BIGINT,
    title VARCHAR(255),
    summary VARCHAR(500),
    content CLOB,
    cover_url VARCHAR(500),
    status INT,
    view_count INT,
    comment_count INT DEFAULT 0,
    like_count INT DEFAULT 0,
    favorite_count INT DEFAULT 0,
    published_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    share_count INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS tags (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100),
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS article_tags (
    article_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (article_id, tag_id)
);

CREATE TABLE IF NOT EXISTS categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100),
    code VARCHAR(100),
    description VARCHAR(500),
    sort_order INT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100),
    password_hash VARCHAR(255),
    nickname VARCHAR(100),
    avatar_url VARCHAR(500),
    bio VARCHAR(500),
    login_type VARCHAR(20) DEFAULT 'password',
    github_id BIGINT NULL,
    status INT,
    followers_count INT DEFAULT 0,
    following_count INT DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_follows (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    follower_id BIGINT NOT NULL,
    following_id BIGINT NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_follow UNIQUE (follower_id, following_id)
);

CREATE INDEX IF NOT EXISTS idx_follower ON user_follows (follower_id, deleted_at);
CREATE INDEX IF NOT EXISTS idx_following ON user_follows (following_id, deleted_at);

CREATE TABLE IF NOT EXISTS article_comments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    content VARCHAR(2000) NOT NULL,
    root_id BIGINT,
    parent_id BIGINT,
    ip VARCHAR(45),
    ip_location VARCHAR(100),
    like_count BIGINT,
    created_at TIMESTAMP,
    deleted_at TIMESTAMP,
    deleted_by BIGINT
);

CREATE TABLE IF NOT EXISTS comment_likes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    comment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS article_likes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time TIMESTAMP
);

CREATE TABLE IF NOT EXISTS article_favorites (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_sessions (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL DEFAULT '新对话',
    active_workflow_run_id BIGINT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_messages (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    workflow_run_id BIGINT,
    role VARCHAR(20) NOT NULL,
    content CLOB NOT NULL,
    page_context CLOB,
    token_count BIGINT,
    created_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_workflow_runs (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT,
    workflow_type VARCHAR(64) NOT NULL,
    workflow_version VARCHAR(32) NOT NULL DEFAULT '1.0',
    status VARCHAR(64) NOT NULL,
    current_step VARCHAR(64),
    context_json CLOB NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    total_tokens INT NOT NULL DEFAULT 0,
    pause_reason VARCHAR(255),
    error_message VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_user_memories (
                                  id BIGINT PRIMARY KEY,
                                  user_id BIGINT NOT NULL,
                                  memory_type VARCHAR(32) NOT NULL,
                                  memory_key VARCHAR(64) NOT NULL,
                                  content CLOB NOT NULL,
                                  source VARCHAR(32) NOT NULL,
                                  confidence DECIMAL(3,2) DEFAULT 1.00,
                                  importance INT DEFAULT 5,
                                  enabled INT DEFAULT 1,
                                  created_at TIMESTAMP,
                                  updated_at TIMESTAMP,
                                  CONSTRAINT uk_user_type_key UNIQUE (user_id, memory_type, memory_key)
);

CREATE INDEX IF NOT EXISTS idx_user_enabled ON ai_user_memories (user_id, enabled);
CREATE INDEX IF NOT EXISTS idx_user_type_enabled ON ai_user_memories (user_id, memory_type, enabled);
CREATE INDEX IF NOT EXISTS idx_user_importance ON ai_user_memories (user_id, enabled, importance);

CREATE TABLE IF NOT EXISTS ai_user_memory_candidates (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id BIGINT,
    message_id BIGINT,
    memory_type VARCHAR(32) NOT NULL,
    memory_key VARCHAR(64) NOT NULL,
    content CLOB NOT NULL,
    candidate_action VARCHAR(32) NOT NULL,
    reason VARCHAR(500),
    decision_reason VARCHAR(500),
    merged_content CLOB,
    source VARCHAR(32) DEFAULT 'AI_EXTRACTED',
    confidence DECIMAL(3,2) DEFAULT 0.80,
    importance INT DEFAULT 5,
    status VARCHAR(32) DEFAULT 'PENDING',
    target_memory_id BIGINT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    decided_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_memory_candidate_user_status ON ai_user_memory_candidates (user_id, status);
CREATE INDEX IF NOT EXISTS idx_memory_candidate_user_type_key ON ai_user_memory_candidates (user_id, memory_type, memory_key);
CREATE INDEX IF NOT EXISTS idx_memory_candidate_target_memory_id ON ai_user_memory_candidates (target_memory_id);
CREATE INDEX IF NOT EXISTS idx_memory_candidate_status_created_at ON ai_user_memory_candidates (status, created_at);


CREATE TABLE IF NOT EXISTS ai_workflow_step_logs (
                                                     id BIGINT NOT NULL PRIMARY KEY COMMENT '主键，使用 MyBatis-Plus ASSIGN_ID',

                                                     workflow_run_id BIGINT NOT NULL COMMENT '对应 ai_workflow_runs.id',

                                                     log_type VARCHAR(20) NOT NULL DEFAULT 'OPERATION' COMMENT '日志类型：OPERATION=操作级 / STEP=步骤级',

                                                     step_order INT NOT NULL COMMENT '步骤顺序',

                                                     step VARCHAR(64) NOT NULL COMMENT '步骤名称，例如 REQUIREMENT_ANALYZE/RAG_SEARCH/GENERATE_DRAFT',

    status VARCHAR(32) NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED/SKIPPED',

    retry_count INT NOT NULL DEFAULT 0 COMMENT '当前步骤重试次数，首次执行为0',

    input_summary TEXT COMMENT '输入摘要',

    output_summary TEXT COMMENT '输出摘要',

    error_message VARCHAR(1000) COMMENT '错误信息',

    metadata_json JSON COMMENT '扩展信息，例如模型、参数、工具信息',

    started_at DATETIME NOT NULL COMMENT '开始时间',

    ended_at DATETIME COMMENT '结束时间',

    duration_ms BIGINT COMMENT '耗时毫秒',

    input_tokens INT NOT NULL DEFAULT 0 COMMENT '输入token',

    output_tokens INT NOT NULL DEFAULT 0 COMMENT '输出token',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_workflow_timeline(workflow_run_id, step_order, id),

    KEY idx_workflow_step_retry(workflow_run_id, step, retry_count),

    KEY idx_workflow_status(status)
    )
    COMMENT='AI Workflow步骤执行日志';

-- 学习计划（LEARNING_PLAN Workflow 业务对象，H2 测试库）
CREATE TABLE IF NOT EXISTS learning_plans (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    goal VARCHAR(500),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    source_workflow_run_id BIGINT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_learning_plans_run_id ON learning_plans(source_workflow_run_id);

CREATE TABLE IF NOT EXISTS learning_stages (
    id BIGINT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    order_num INT NOT NULL DEFAULT 0,
    title VARCHAR(200) NOT NULL,
    tasks CLOB,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_learning_stages_plan ON learning_stages(plan_id, order_num);

CREATE TABLE IF NOT EXISTS ai_episodic_memories (
                                                    id BIGINT PRIMARY KEY,
                                                    user_id BIGINT NOT NULL,
                                                    session_id BIGINT,
                                                    project_key VARCHAR(64) NOT NULL DEFAULT '01myBlog',
    memory_type VARCHAR(32) NOT NULL,
    title VARCHAR(120) NOT NULL,
    content CLOB NOT NULL,
    importance INT NOT NULL DEFAULT 6,
    confidence DECIMAL(3,2) NOT NULL DEFAULT 0.80,
    source_message_ids CLOB,
    content_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    last_retrieved_at TIMESTAMP,
    retrieval_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_episodic_user_project_hash UNIQUE (user_id, project_key, content_hash)
    );

CREATE INDEX IF NOT EXISTS idx_episodic_user_project_time
    ON ai_episodic_memories (user_id, project_key, occurred_at);
CREATE INDEX IF NOT EXISTS idx_episodic_user_type
    ON ai_episodic_memories (user_id, memory_type);
CREATE INDEX IF NOT EXISTS idx_episodic_importance
    ON ai_episodic_memories (user_id, project_key, importance);


CREATE TABLE IF NOT EXISTS ai_conversation_summaries (
                                                         id BIGINT PRIMARY KEY,
                                                         user_id BIGINT NOT NULL,
                                                         session_id BIGINT NOT NULL,
                                                         summary CLOB NOT NULL,
                                                         summary_json CLOB,
                                                         covered_until_message_id BIGINT,
                                                         covered_message_count INT NOT NULL DEFAULT 0,
                                                         version INT NOT NULL DEFAULT 1,
                                                         last_compressed_at TIMESTAMP,
                                                         compressing BOOLEAN NOT NULL DEFAULT FALSE,
                                                         created_at TIMESTAMP NOT NULL,
                                                         updated_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_conversation_summary_session
    ON ai_conversation_summaries (session_id);

CREATE INDEX IF NOT EXISTS idx_ai_conversation_summary_user_session
    ON ai_conversation_summaries (user_id, session_id);
