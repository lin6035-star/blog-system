package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.AiBillingOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * AI 计费单 Mapper。
 *
 * <p>两道幂等分工（设计稿 §2④）：
 * <ul>
 *   <li>{@code uk_biz (biz_type, biz_id)} —— 防「重复预扣」（同一次业务调用开出两张单）</li>
 *   <li>{@code status = 'RESERVED'} 条件更新 —— 防「重复结算/释放」（同一张单被结两次）</li>
 * </ul>
 */
@Mapper
public interface AiBillingOrderMapper extends BaseMapper<AiBillingOrder> {

    /**
     * 结算：只有仍是 RESERVED 才推进到 SETTLED。
     *
     * <p>影响行数 0 表示这张单已经被释放（超时兜底抢先）或已结算过——
     * 两种情况都<b>不能再退一次钱</b>，调用方据此跳过退款。
     */
    @Update("""
            UPDATE ai_billing_order
            SET status = 'SETTLED',
                actual_credit = #{actualCredit},
                total_tokens = #{totalTokens},
                prompt_tokens = #{promptTokens},
                completion_tokens = #{completionTokens},
                resource_id = COALESCE(#{resourceId,jdbcType=VARCHAR}, resource_id),
                version = version + 1,
                settled_at = CURRENT_TIMESTAMP
            WHERE order_no = #{orderNo}
              AND status = 'RESERVED'
            """)
    int markSettled(@Param("orderNo") String orderNo,
                    @Param("actualCredit") long actualCredit,
                    @Param("totalTokens") int totalTokens,
                    @Param("promptTokens") int promptTokens,
                    @Param("completionTokens") int completionTokens,
                    @Param("resourceId") String resourceId);

    /** 释放（失败 / 取消 / 超时兜底）：同样只在 RESERVED 时才允许退款 */
    @Update("""
            UPDATE ai_billing_order
            SET status = 'RELEASED',
                version = version + 1,
                settled_at = CURRENT_TIMESTAMP
            WHERE order_no = #{orderNo}
              AND status = 'RESERVED'
            """)
    int markReleased(@Param("orderNo") String orderNo);

    /** 回填关联资源（观测用） */
    @Update("""
            UPDATE ai_billing_order
            SET resource_id = #{resourceId}
            WHERE order_no = #{orderNo}
            """)
    int bindResource(@Param("orderNo") String orderNo, @Param("resourceId") String resourceId);
}
