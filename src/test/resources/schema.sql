DROP TABLE IF EXISTS article_tags;
DROP TABLE IF EXISTS comment_likes;
DROP TABLE IF EXISTS article_likes;
DROP TABLE IF EXISTS article_favorites;
DROP TABLE IF EXISTS article_comments;
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
    comment_count INT DEFAULT 0,
    like_count INT DEFAULT 0,
    favorite_count INT DEFAULT 0,
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

CREATE TABLE article_comments (
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

CREATE TABLE comment_likes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    comment_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP
);

CREATE TABLE article_likes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time TIMESTAMP
);

CREATE TABLE article_favorites (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    article_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    create_time TIMESTAMP
);
