package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.UserWallet;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 钱包账户 Mapper。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §3
 *
 * <p>余额变更一律走条件更新（不是「先查询再判断」）——条件更新由 DB 串行执行，
 * 第二个并发请求看到的是第一个扣完之后的余额，不可能两个请求凭同一份余额各扣一次。
 */
@Mapper
public interface UserWalletMapper extends BaseMapper<UserWallet> {

    /**
     * 条件扣减。
     *
     * <p>⚠️ 门槛是 {@code balance > 0}，<b>不是</b> {@code balance >= amount}——
     * 因为 amount 是「硬上限」不是报价，用刚性门槛会拒绝本可支付的请求（设计稿 §3）。
     * 放行后余额扣成负数，下一次 {@code balance > 0} 不成立，自动拦住。
     *
     * <p>影响行数 0 = 余额 ≤ 0 或钱包行不存在，两者语义相同：不能发起这次调用。
     * 不重试——「够就扣」本身就是最终判断，返回 0 行是余额真不够，不是并发冲突。
     */
    @Update("""
            UPDATE user_wallet
            SET balance = balance - #{amount},
                balance_seq = balance_seq + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND balance > 0
            """)
    int deduct(@Param("userId") Long userId, @Param("amount") long amount);

    /**
     * 无条件入账（充值 / 秒杀发放）。
     *
     * <p>调用前必须先 ensureWallet——钱包行不存在时影响行数为 0。
     */
    @Update("""
            UPDATE user_wallet
            SET balance = balance + #{amount},
                balance_seq = balance_seq + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
            """)
    int add(@Param("userId") Long userId, @Param("amount") long amount);

    /**
     * 创建钱包行（余额 0）。
     *
     * <p>并发下可能撞 {@code uk_user_id}，由调用方 catch {@code DuplicateKeyException} 当作幂等成功。
     * <b>不用「先 selectCount 再插入」</b>——那在并发下有竞态，而这里唯一索引就是防线。
     */
    @Insert("INSERT INTO user_wallet (id, user_id, balance, balance_seq) VALUES (#{id}, #{userId}, 0, 0)")
    int insertWallet(@Param("id") Long id, @Param("userId") Long userId);
}
