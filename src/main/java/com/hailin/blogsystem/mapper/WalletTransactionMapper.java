package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.WalletTransaction;
import com.hailin.blogsystem.entity.vo.WalletBillEntryVO;
import com.hailin.blogsystem.entity.vo.WalletLedgerAnomalyVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 钱包流水 Mapper。只增不改。
 *
 * <p>唯一索引 {@code uk_user_idem (user_id, idempotency_key)} 和
 * {@code uk_user_seq (user_id, balance_seq)} 是幂等与漏流水的最终防线。
 */
@Mapper
public interface WalletTransactionMapper extends BaseMapper<WalletTransaction> {

    /**
     * 用户账单：按 {@code biz_id} 聚合后的净额。
     *
     * <p>两个过滤条件是这段查询的全部要点：
     * <ul>
     *   <li><b>预扣数 = 退款数</b>——把「还在预扣中」（只有 AI_RESERVE，没有配对退款）
     *       的条目挡在外面。净额没定下来的不是已发生的事实，不该进账单。
     *       写成计数相等而不是判断 type，是因为一张单最终可能是 SETTLED（部分退）
     *       也可能是 RELEASED（全额退），两者都恰好一进一出。</li>
     *   <li><b>净额不为 0</b>——全额退回的（取消的对话）在账单里没有意义。</li>
     * </ul>
     *
     * <p>{@code MAX(balance_seq)} 对应的那一行就是这笔业务结算后的状态，
     * 靠 {@code uk_user_seq} 唯一索引 JOIN 回来取余额快照。
     */
    @Select("""
            SELECT agg.biz_id       AS biz_id,
                   agg.biz_type     AS biz_type,
                   agg.amount       AS amount,
                   w.balance_after  AS balance_after,
                   agg.created_at   AS created_at
            FROM (
                SELECT biz_id,
                       MAX(biz_type)    AS biz_type,
                       SUM(amount)      AS amount,
                       MAX(balance_seq) AS max_seq,
                       MAX(created_at)  AS created_at
                FROM wallet_transaction
                WHERE user_id = #{userId}
                GROUP BY biz_id
                HAVING SUM(CASE WHEN type = 'AI_RESERVE' THEN 1 ELSE 0 END)
                     = SUM(CASE WHEN type = 'AI_REFUND' THEN 1 ELSE 0 END)
                   AND SUM(amount) <> 0
            ) agg
            JOIN wallet_transaction w
              ON w.user_id = #{userId}
             AND w.balance_seq = agg.max_seq
            ORDER BY agg.created_at DESC, agg.max_seq DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<WalletBillEntryVO> selectBillPage(@Param("userId") Long userId,
                                           @Param("limit") long limit,
                                           @Param("offset") long offset);

    /** 账单总条数——口径必须与 {@link #selectBillPage} 的过滤条件完全一致 */
    @Select("""
            SELECT COUNT(*)
            FROM (
                SELECT biz_id
                FROM wallet_transaction
                WHERE user_id = #{userId}
                GROUP BY biz_id
                HAVING SUM(CASE WHEN type = 'AI_RESERVE' THEN 1 ELSE 0 END)
                     = SUM(CASE WHEN type = 'AI_REFUND' THEN 1 ELSE 0 END)
                   AND SUM(amount) <> 0
            ) c
            """)
    long countBill(@Param("userId") Long userId);

    /**
     * 全表扫出一行都接不上的流水（对账用，设计稿 §7.3）。
     *
     * <p>用窗口函数把「前一笔」拉到同一行上，判定在 SQL 里做完，不把全表捞进内存。
     * 判两条：
     * <ul>
     *   <li>{@code prev_seq + 1 = balance_seq} —— 序号连号，<b>没有漏流水</b></li>
     *   <li>{@code prev_balance + amount = balance_after} —— 余额快照接得上</li>
     * </ul>
     *
     * <p>⚠️ {@code prev_seq IS NULL} 的行（每个用户的第一笔）会被跳过——它没有前驱可比。
     * 这个盲区由 {@link #selectUsersWithMissingEarlyTransactions} 补上。
     *
     * <p>⚠️ <b>全表扫</b>，随流水增长会越来越慢。当前数据量下可接受（1 小时一轮）；
     * 真到百万级要改成按 {@code user_id} 分批，用 {@code idx_wallet_tx_user} 走索引逐一校验。
     */
    @Select("""
            SELECT user_id, balance_seq, amount, balance_after, prev_balance, prev_seq
            FROM (
                SELECT user_id,
                       balance_seq,
                       amount,
                       balance_after,
                       LAG(balance_after) OVER (PARTITION BY user_id ORDER BY balance_seq) AS prev_balance,
                       LAG(balance_seq)   OVER (PARTITION BY user_id ORDER BY balance_seq) AS prev_seq
                FROM wallet_transaction
            ) ledger
            WHERE prev_seq IS NOT NULL
              AND (prev_seq <> balance_seq - 1 OR prev_balance + amount <> balance_after)
            LIMIT #{limit}
            """)
    List<WalletLedgerAnomalyVO> selectLedgerAnomalies(@Param("limit") int limit);

    /**
     * 最早一笔不是第 1 号的用户。
     *
     * <p><b>补的正是 {@link #selectLedgerAnomalies} 够不着的那一格</b>：{@code LAG} 对每个用户的
     * 第一笔返回 {@code NULL}，而那恰好是「更早的流水被删掉了」时会留下的那一行。
     * 删掉开头几笔之后，剩下的序列仍然首尾自洽——只有「首笔必须是 1」这个不变量能发现。
     *
     * <p>序号从 1 起是 {@code applyChange} 保证的（钱包行创建时 {@code balance_seq = 0}，
     * 第一次变更读写成 1），所以这里能把它当硬约束用。
     */
    @Select("""
            SELECT user_id
            FROM wallet_transaction
            GROUP BY user_id
            HAVING MIN(balance_seq) <> 1
            LIMIT #{limit}
            """)
    List<Long> selectUsersWithMissingEarlyTransactions(@Param("limit") int limit);
}
