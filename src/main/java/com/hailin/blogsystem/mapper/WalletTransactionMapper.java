package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.WalletTransaction;
import com.hailin.blogsystem.entity.vo.WalletBillEntryVO;
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
}
