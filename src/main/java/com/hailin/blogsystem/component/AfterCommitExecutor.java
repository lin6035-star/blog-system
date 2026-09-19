package com.hailin.blogsystem.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把"数据库之外的动作"推迟到事务提交之后再执行（无事务时立即执行）。
 *
 * **它解决什么**：Cache-Aside 的"写库后删缓存"如果发生在事务内，会留下一个必然出现的窗口——
 *
 * <pre>
 * 事务内更新 DB → 删除缓存 → 另一请求 miss，读到"尚未提交"的旧值并回填
 *   → 原事务提交 → 缓存里留着旧值，直到 TTL 过期
 * </pre>
 *
 * 注意这不是"概率很低"的竞态：删缓存之后到事务提交之前的这段时间里，任何一次缓存 miss
 * 都会走上面这条链，窗口由事务本身的耗时决定（含后续的 RAG 同步等操作）。
 * 提交后再删就不存在这个窗口——删除动作执行时，DB 里已经是可见的新值。
 *
 * **顺带修掉一个副作用**：事务回滚时不再白删缓存（旧实现已经删了，而数据其实没变）。
 *
 * **为什么不用 `@TransactionalEventListener(AFTER_COMMIT)`**：事务事件在**没有事务时默认不执行**。
 * 本项目大量缓存失效发生在非事务方法里（删文章 / 隐藏 / 发布 / 评论 / 收藏），
 * 一旦有人给某个方法去掉 `@Transactional`，缓存就**永远不再失效**——这种失效是静默的，
 * 比"删早了"更难发现。这里的 fallback 是显式的：**没有事务就地执行**，行为与改造前逐字节一致。
 *
 * **边界**：
 * - 动作必须只碰 Redis / 内存。事务已提交，此时再访问 DB 会另开事务，读到的是提交后的新数据，
 *   但把 DB 写操作放在这里等于绕过了原事务的原子性——要用的话得先想清楚。
 * - {@link #execute} 内部会兜住异常：`afterCommit` 里抛出去的异常会传播到 `commit()` 的调用方，
 *   让一次**已经提交成功**的操作返回失败，那是比缓存脏更糟的结果。
 */
@Slf4j
@Component
public class AfterCommitExecutor {

    /**
     * 提交后执行（无事务则立即执行）。
     *
     * @param action 只操作 Redis / 内存的动作。**不要依赖它一定在提交后运行**——
     *               非事务调用方走的是立即执行分支，业务逻辑不能建立在"它被延迟了"之上
     */
    public void execute(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (Exception e) {
                    // 必须在这里兜住：异常会一路传播到 commit() 的调用方，
                    // 于是"数据已提交成功"变成"接口报错"，比缓存脏更难排查
                    log.error("[AFTER-COMMIT] 提交后动作执行失败（业务已提交，不受影响）", e);
                }
            }
        });
    }
}
