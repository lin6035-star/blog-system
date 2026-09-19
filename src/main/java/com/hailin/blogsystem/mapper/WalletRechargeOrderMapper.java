package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.WalletRechargeOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 充值订单 Mapper。
 *
 * <p>「置 PAID」用<b>状态 CAS</b> 而不是无条件 UPDATE：并发双击时只有一个事务能推进状态，
 * 另一个影响 0 行，据此走幂等分支——否则同一张订单会被加两次余额。
 */
@Mapper
public interface WalletRechargeOrderMapper extends BaseMapper<WalletRechargeOrder> {

    /**
     * 状态 CAS：只有仍是 PENDING 才置 PAID。
     *
     * @return 影响行数。0 表示已被别的请求改过状态（并发双击，或订单已取消）
     */
    @Update("""
            UPDATE wallet_recharge_order
            SET status = 'PAID',
                version = version + 1,
                paid_at = CURRENT_TIMESTAMP
            WHERE order_no = #{orderNo}
              AND status = 'PENDING'
            """)
    int markPaid(@Param("orderNo") String orderNo);

    /**
     * 锁定读订单。
     *
     * <p>CAS 影响 0 行之后要靠它拿到<b>已提交的最新状态</b>：MySQL 默认 REPEATABLE READ 下，
     * 普通 SELECT 读到的是事务开始时的快照，可能还停在 PENDING，据此判断会得出错误结论。
     */
    @Select("SELECT * FROM wallet_recharge_order WHERE order_no = #{orderNo} FOR UPDATE")
    WalletRechargeOrder selectByOrderNoForUpdate(@Param("orderNo") String orderNo);
}
