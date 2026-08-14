package com.hailin.blogsystem.component;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hailin.blogsystem.ai.memory.AiMemoryIndexService;
import com.hailin.blogsystem.ai.rag.ArticleRagSyncService;
import com.hailin.blogsystem.entity.AiUserMemories;
import com.hailin.blogsystem.mapper.AiUserMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次性迁移：将 categories 和 tags 的自增旧 ID 替换为雪花 ID。
 * <p>
 * 迁移完成后请删除此类，避免每次启动都执行检查。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "blog.migration", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IdMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ArticleRagSyncService articleRagSyncService;
    private final AiUserMemoryMapper aiUserMemoryMapper;
    private final AiMemoryIndexService aiMemoryIndexService;

    /** 旧 ID 阈值：小于此值的 ID 认为是旧的自增 ID */
    private static final long OLD_ID_THRESHOLD = 100_000_000_000L;

    @Override
    public void run(ApplicationArguments args) {
        final boolean[] migrated = {false};
        transactionTemplate.executeWithoutResult(status -> {
            if (needsMigration("categories")) {
                migrateCategories();
                migrated[0] = true;
            }
            if (needsMigration("tags")) {
                migrateTags();
                migrated[0] = true;
            }
            if (needsMigration("article_comments")) {
                migrateComments();
                migrated[0] = true;
            }
            if (needsMigration("articles")) {
                migrateArticles();
                migrated[0] = true;
            }
            if (needsMigration("users")) {
                migrateUsers();
                migrated[0] = true;
            }
        });

        // 事务外执行：本次发生了迁移才清理缓存并重建 ES 索引
        if (migrated[0]) {
            cleanupRedisAfterMigration();
            triggerRagRebuild();
            rebuildMemoryIndexes();
        }

        log.info("雪花 ID 迁移检查完成");
    }

    // ================================================================
    // check
    // ================================================================

    private boolean needsMigration(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id < ?",
                Integer.class, OLD_ID_THRESHOLD);
        return count != null && count > 0;
    }

    // ================================================================
    // categories
    // ================================================================

    private void migrateCategories() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name FROM categories WHERE id < ?", OLD_ID_THRESHOLD);

        Map<Long, Long> idMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long oldId = ((Number) row.get("id")).longValue();
            Long newId = IdWorker.getId();
            idMap.put(oldId, newId);
            log.info("Category ID 映射: {} → {} ({})", oldId, newId, row.get("name"));
        }

        if (idMap.isEmpty()) {
            return;
        }

        // ① 更新 articles 外键
        for (Map.Entry<Long, Long> entry : idMap.entrySet()) {
            jdbcTemplate.update("UPDATE articles SET category_id = ? WHERE category_id = ?",
                    entry.getValue(), entry.getKey());
        }

        // ② 更新 categories 主键
        for (Map.Entry<Long, Long> entry : idMap.entrySet()) {
            jdbcTemplate.update("UPDATE categories SET id = ? WHERE id = ?",
                    entry.getValue(), entry.getKey());
        }

        log.info("categories 迁移完成，共 {} 条", idMap.size());
    }

    // ================================================================
    // tags
    // ================================================================

    private void migrateTags() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name FROM tags WHERE id < ?", OLD_ID_THRESHOLD);

        Map<Long, Long> idMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long oldId = ((Number) row.get("id")).longValue();
            Long newId = IdWorker.getId();
            idMap.put(oldId, newId);
            log.info("Tag ID 映射: {} → {} ({})", oldId, newId, row.get("name"));
        }

        if (idMap.isEmpty()) {
            return;
        }

        // ① 更新 article_tags 外键
        for (Map.Entry<Long, Long> entry : idMap.entrySet()) {
            jdbcTemplate.update("UPDATE article_tags SET tag_id = ? WHERE tag_id = ?",
                    entry.getValue(), entry.getKey());
        }

        // ② 更新 tags 主键
        for (Map.Entry<Long, Long> entry : idMap.entrySet()) {
            jdbcTemplate.update("UPDATE tags SET id = ? WHERE id = ?",
                    entry.getValue(), entry.getKey());
        }

        log.info("tags 迁移完成，共 {} 条", idMap.size());
    }

    // ================================================================
    // article_comments（自引用 root_id/parent_id，波及 comment_likes.comment_id）
    // ================================================================

    private void migrateComments() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM article_comments WHERE id < ?", OLD_ID_THRESHOLD);

        Map<Long, Long> idMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long oldId = ((Number) row.get("id")).longValue();
            idMap.put(oldId, IdWorker.getId());
        }

        if (idMap.isEmpty()) {
            return;
        }

        // ① 自引用：root_id / parent_id 指向旧 id，先改引用
        for (Map.Entry<Long, Long> entry : idMap.entrySet()) {
            jdbcTemplate.update("UPDATE article_comments SET root_id = ? WHERE root_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE article_comments SET parent_id = ? WHERE parent_id = ?",
                    entry.getValue(), entry.getKey());
        }

        // ② 波及表：comment_likes.comment_id 指向评论 id
        for (Map.Entry<Long, Long> entry : idMap.entrySet()) {
            jdbcTemplate.update("UPDATE comment_likes SET comment_id = ? WHERE comment_id = ?",
                    entry.getValue(), entry.getKey());
        }

        // ③ 最后改主键
        for (Map.Entry<Long, Long> entry : idMap.entrySet()) {
            jdbcTemplate.update("UPDATE article_comments SET id = ? WHERE id = ?",
                    entry.getValue(), entry.getKey());
        }

        log.info("article_comments 迁移完成，共 {} 条（含 root_id/parent_id 自引用与 comment_likes 波及）", idMap.size());
    }

    // ================================================================
    // articles（波及 article_tags / article_likes / article_favorites / article_comments）
    // ================================================================

    private void migrateArticles() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, title FROM articles WHERE id < ?", OLD_ID_THRESHOLD);

        Map<Long, Long> idMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long oldId = ((Number) row.get("id")).longValue();
            Long newId = IdWorker.getId();
            idMap.put(oldId, newId);
            log.info("Article ID 映射: {} → {} ({})", oldId, newId, row.get("title"));
        }

        if (idMap.isEmpty()) {
            return;
        }

        // ① 波及表外键：article_tags / article_likes / article_favorites / article_comments
        //    先改引用再改主键；这几张表无外键约束，仅唯一键，且已确认无重复数据
        for (Map.Entry<Long, Long> entry : idMap.entrySet()) {
            jdbcTemplate.update("UPDATE article_tags SET article_id = ? WHERE article_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE article_likes SET article_id = ? WHERE article_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE article_favorites SET article_id = ? WHERE article_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE article_comments SET article_id = ? WHERE article_id = ?",
                    entry.getValue(), entry.getKey());
        }

        // ② 更新 articles 主键
        for (Map.Entry<Long, Long> entry : idMap.entrySet()) {
            jdbcTemplate.update("UPDATE articles SET id = ? WHERE id = ?",
                    entry.getValue(), entry.getKey());
        }

        log.info("articles 迁移完成，共 {} 条（含 article_tags/article_likes/article_favorites/article_comments 波及）", idMap.size());
    }

    // ================================================================
    // users（波及文章/评论/点赞/收藏/关注/AI 会话/AI 记忆/AI 工作流）
    // ================================================================

    private void migrateUsers() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, username FROM users WHERE id < ?", OLD_ID_THRESHOLD);

        Map<Long, Long> idMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long oldId = ((Number) row.get("id")).longValue();
            Long newId = IdWorker.getId();
            idMap.put(oldId, newId);
            log.info("User ID 映射: {} → {} ({})", oldId, newId, row.get("username"));
        }

        if (idMap.isEmpty()) {
            return;
        }

        // ① 引用表外键：先改引用再改主键
        //    user_follows 有两个外键（follower_id / following_id），uk_follow 组合不冲突
        for (Map.Entry<Long, Long> entry : idMap.entrySet()) {
            jdbcTemplate.update("UPDATE articles SET author_id = ? WHERE author_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE article_comments SET user_id = ? WHERE user_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE article_likes SET user_id = ? WHERE user_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE article_favorites SET user_id = ? WHERE user_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE comment_likes SET user_id = ? WHERE user_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE user_follows SET follower_id = ? WHERE follower_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE user_follows SET following_id = ? WHERE following_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE ai_sessions SET user_id = ? WHERE user_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE ai_user_memories SET user_id = ? WHERE user_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE ai_user_memory_candidates SET user_id = ? WHERE user_id = ?",
                    entry.getValue(), entry.getKey());
            jdbcTemplate.update("UPDATE ai_workflow_runs SET user_id = ? WHERE user_id = ?",
                    entry.getValue(), entry.getKey());
        }

        // ② 更新 users 主键
        for (Map.Entry<Long, Long> entry : idMap.entrySet()) {
            jdbcTemplate.update("UPDATE users SET id = ? WHERE id = ?",
                    entry.getValue(), entry.getKey());
        }

        log.info("users 迁移完成，共 {} 条（含 11 处引用表外键波及）", idMap.size());
    }

    // ================================================================
    // 迁移后的缓存清理与 ES 索引重建
    // ================================================================

    /**
     * 迁移后删除包含旧 ID 的 Redis 缓存：
     * article:list:*（列表缓存里是旧文章 ID，不删会打开失败）
     * article:hot（热榜成员是旧 ID，删除后接口走 DB 兜底重建）
     * article:liked:user:* / article:favorited:user:*（key 里的 userId 与 set 里的 articleId 都是旧值）
     * comment:liked:user:*（key 里的 userId 是旧值）
     * article:detail:* / article:view:* / comment:list:article:*（旧 ID 的 key，顺手清掉）
     */
    private void cleanupRedisAfterMigration() {
        try {
            Set<String> keys = new HashSet<>(stringRedisTemplate.keys("article:*"));
            keys.addAll(stringRedisTemplate.keys("comment:list:article:*"));
            keys.addAll(stringRedisTemplate.keys("comment:liked:user:*"));

            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
                log.info("已清理迁移相关 Redis 缓存，共 {} 个 key", keys.size());
            }
        } catch (Exception e) {
            log.warn("Redis 缓存清理失败（不影响迁移，可稍后手动清理）：{}", e.getMessage());
        }
    }

    /**
     * ES 记忆索引里 metadata.userId 还是旧 ID：逐条重新索引。
     * indexMemory 内部会先按 memoryId 删除旧文档再写入，自动带上新 userId。
     */
    private void rebuildMemoryIndexes() {
        try {
            List<AiUserMemories> memories = aiUserMemoryMapper.selectList(
                    Wrappers.<AiUserMemories>lambdaQuery()
                            .eq(AiUserMemories::getEnabled, 1));

            for (AiUserMemories memory : memories) {
                aiMemoryIndexService.indexMemory(memory);
            }
            log.info("ES 记忆索引重建完成，共 {} 条", memories.size());
        } catch (Exception e) {
            log.warn("ES 记忆索引重建失败（不影响迁移，可稍后手动处理）：{}", e.getMessage());
        }
    }

    /**
     * ES 索引里 metadata.articleId 还是旧 ID，全量重建后刷新为新 ID。
     * 异步执行，内部自带重试和失败标记，失败不影响启动。
     */
    private void triggerRagRebuild() {
        try {
            articleRagSyncService.rebuildPublishedArticles();
            log.info("已触发 RAG 索引全量重建（异步）");
        } catch (Exception e) {
            log.warn("触发 RAG 索引重建失败：{}", e.getMessage());
        }
    }
}
