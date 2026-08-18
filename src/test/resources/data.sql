INSERT IGNORE INTO articles (
    id, category_id, author_id, title, summary, content, cover_url,
    status, view_count, published_at, created_at, updated_at, deleted_at
) VALUES (
    1, 1, 100, 'Published Article', 'Published summary', 'Published content', 'https://example.com/cover.png',
    1, 12, '2026-07-02 10:00:00', '2026-07-02 09:00:00', '2026-07-02 09:30:00', NULL
);

INSERT IGNORE INTO articles (
    id, category_id, author_id, title, summary, content, cover_url,
    status, view_count, published_at, created_at, updated_at, deleted_at
) VALUES (
    2, 1, 100, 'Draft Article', 'Draft summary', 'Draft content', NULL,
    0, 0, NULL, '2026-07-02 09:00:00', '2026-07-02 09:30:00', NULL
);

INSERT IGNORE INTO articles (
    id, category_id, author_id, title, summary, content, cover_url,
    status, view_count, published_at, created_at, updated_at, deleted_at
) VALUES (
    3, 1, 100, 'Popular Article', 'Popular summary', 'Popular content', NULL,
    1, 100, '2026-07-01 10:00:00', '2026-07-01 09:00:00', '2026-07-01 09:30:00', NULL
);

INSERT IGNORE INTO articles (
    id, category_id, author_id, title, summary, content, cover_url,
    status, view_count, published_at, created_at, updated_at, deleted_at
) VALUES (
    4, 1, 100, 'Latest Article', 'Latest summary', 'Latest content', NULL,
    1, 1, '2026-07-03 10:00:00', '2026-07-03 09:00:00', '2026-07-03 09:30:00', NULL
);

INSERT IGNORE INTO categories (id, name, code, description, sort_order, created_at, updated_at)
VALUES (1, 'AI / Agent', 'ai-agent', 'AI、Agent、coding agent 相关内容', 1, '2026-07-02 09:00:00', '2026-07-02 09:00:00');

INSERT IGNORE INTO categories (id, name, code, description, sort_order, created_at, updated_at)
VALUES (2, 'Java 后端', 'java-backend', 'Java、Spring Boot、后端开发相关内容', 2, '2026-07-02 09:00:00', '2026-07-02 09:00:00');

INSERT IGNORE INTO categories (id, name, code, description, sort_order, created_at, updated_at)
VALUES (3, '项目实战', 'project-practice', '项目开发、部署、踩坑记录', 3, '2026-07-02 09:00:00', '2026-07-02 09:00:00');

INSERT IGNORE INTO categories (id, name, code, description, sort_order, created_at, updated_at)
VALUES (4, '随笔', 'notes', '临时想法、学习记录、杂谈', 4, '2026-07-02 09:00:00', '2026-07-02 09:00:00');

INSERT IGNORE INTO users (
    id, username, password_hash, nickname, avatar_url, bio, status, created_at, updated_at, deleted_at
) VALUES (
    1, 'existing', '$2a$10$gVwQ5x1Yz2X3y4Z5a6B7cO8P9qR0sT1uV2wX3yZ4a5B6c7D8e9F0G', 'Existing User',
    NULL, NULL, 1, '2026-07-02 09:00:00', '2026-07-02 09:00:00', NULL
);

INSERT IGNORE INTO users (
    id, username, password_hash, nickname, avatar_url, bio, status, created_at, updated_at, deleted_at
) VALUES (
    100, 'author', '$2a$10$gVwQ5x1Yz2X3y4Z5a6B7cO8P9qR0sT1uV2wX3yZ4a5B6c7D8e9F0G', 'Author Nick',
    NULL, NULL, 1, '2026-07-02 09:00:00', '2026-07-02 09:00:00', NULL
);

INSERT IGNORE INTO users (
    id, username, password_hash, nickname, avatar_url, bio, status, created_at, updated_at, deleted_at
) VALUES (
    101, 'reader', '$2a$10$gVwQ5x1Yz2X3y4Z5a6B7cO8P9qR0sT1uV2wX3yZ4a5B6c7D8e9F0G', 'Reader Nick',
    NULL, NULL, 1, '2026-07-02 09:00:00', '2026-07-02 09:00:00', NULL
);

INSERT IGNORE INTO users (
    id, username, password_hash, nickname, avatar_url, bio, status, created_at, updated_at, deleted_at
) VALUES (
    102, 'follower', '$2a$10$gVwQ5x1Yz2X3y4Z5a6B7cO8P9qR0sT1uV2wX3yZ4a5B6c7D8e9F0G', 'Follower Nick',
    NULL, 'Follower bio', 1, '2026-07-02 09:00:00', '2026-07-02 09:00:00', NULL
);

INSERT IGNORE INTO user_follows (
    id, follower_id, following_id, created_at, updated_at, deleted_at
) VALUES (
    1, 100, 101, '2026-07-03 09:00:00', '2026-07-03 09:00:00', NULL
);

INSERT IGNORE INTO user_follows (
    id, follower_id, following_id, created_at, updated_at, deleted_at
) VALUES (
    2, 102, 100, '2026-07-04 09:00:00', '2026-07-04 09:00:00', NULL
);

INSERT IGNORE INTO user_follows (
    id, follower_id, following_id, created_at, updated_at, deleted_at
) VALUES (
    3, 101, 100, '2026-07-05 09:00:00', '2026-07-05 10:00:00', '2026-07-05 10:00:00'
);
