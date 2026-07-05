INSERT INTO articles (
    id, category_id, author_id, title, summary, content, cover_url,
    status, view_count, published_at, created_at, updated_at, deleted_at
) VALUES (
    1, 10, 100, 'Published Article', 'Published summary', 'Published content', 'https://example.com/cover.png',
    1, 12, '2026-07-02 10:00:00', '2026-07-02 09:00:00', '2026-07-02 09:30:00', NULL
);

INSERT INTO articles (
    id, category_id, author_id, title, summary, content, cover_url,
    status, view_count, published_at, created_at, updated_at, deleted_at
) VALUES (
    2, 10, 100, 'Draft Article', 'Draft summary', 'Draft content', NULL,
    0, 0, NULL, '2026-07-02 09:00:00', '2026-07-02 09:30:00', NULL
);

INSERT INTO articles (
    id, category_id, author_id, title, summary, content, cover_url,
    status, view_count, published_at, created_at, updated_at, deleted_at
) VALUES (
    3, 10, 100, 'Popular Article', 'Popular summary', 'Popular content', NULL,
    1, 100, '2026-07-01 10:00:00', '2026-07-01 09:00:00', '2026-07-01 09:30:00', NULL
);

INSERT INTO articles (
    id, category_id, author_id, title, summary, content, cover_url,
    status, view_count, published_at, created_at, updated_at, deleted_at
) VALUES (
    4, 10, 100, 'Latest Article', 'Latest summary', 'Latest content', NULL,
    1, 1, '2026-07-03 10:00:00', '2026-07-03 09:00:00', '2026-07-03 09:30:00', NULL
);

INSERT INTO categories (id, name, code, description, sort_order, created_at, updated_at)
VALUES (1, 'Backend', 'backend', 'Java backend content', 2, '2026-07-02 09:00:00', '2026-07-02 09:00:00');

INSERT INTO categories (id, name, code, description, sort_order, created_at, updated_at)
VALUES (2, 'AI', 'ai', 'AI and Agent content', 1, '2026-07-02 09:00:00', '2026-07-02 09:00:00');

INSERT INTO users (
    id, username, password_hash, nickname, avatar_url, bio, status, created_at, updated_at, deleted_at
) VALUES (
    1, 'existing', '$2a$10$gVwQ5x1Yz2X3y4Z5a6B7cO8P9qR0sT1uV2wX3yZ4a5B6c7D8e9F0G', 'Existing User',
    NULL, NULL, 1, '2026-07-02 09:00:00', '2026-07-02 09:00:00', NULL
);
