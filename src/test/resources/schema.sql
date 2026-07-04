DROP TABLE IF EXISTS article_tags;
DROP TABLE IF EXISTS articles;
DROP TABLE IF EXISTS categories;
DROP TABLE IF EXISTS users;

CREATE TABLE articles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT,
    author_id BIGINT,
    title VARCHAR(255),
    summary VARCHAR(500),
    content CLOB,
    cover_url VARCHAR(500),
    status INT,
    view_count INT,
    published_at TIMESTAMP,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE TABLE categories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100),
    code VARCHAR(100),
    description VARCHAR(500),
    sort_order INT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100),
    password_hash VARCHAR(255),
    nickname VARCHAR(100),
    avatar_url VARCHAR(500),
    bio VARCHAR(500),
    status INT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);
