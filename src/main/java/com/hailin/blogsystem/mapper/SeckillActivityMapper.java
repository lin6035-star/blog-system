package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀活动 Mapper。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §6.4
 */
@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /**
     * 最终裁决：条件扣减库存。
     *
     * <p>⚠️ 条件里引用 {@code total_stock} <b>列</b>，不要把总库存当参数传进来——
     * 传参意味着判定依据来自调用方，一旦传入值和行内值不一致（活动被改过库存、
     * 参数拼错），限制就静默失效了。让 DB 用自己那一行做判断。
     *
     * @return 影响行数；<b>0 = DB 说已售罄</b>，这是业务失败不是技术失败，
     *         处置方式完全相反（见设计稿 §6.5）
     */
    @Update("""
            UPDATE seckill_activity
            SET sold_count = sold_count + 1
            WHERE id = #{activityId}
              AND sold_count < total_stock
            """)
    int increaseSoldCount(@Param("activityId") Long activityId);

    /**
     * 回滚库存（把上面那 +1 减回去）。
     *
     * <p>只在<b>同一事务内</b>的后续步骤失败时调用——事务一提交，
     * 「已发放」就是既成事实，那时再退库存等于把同一个名额发给两个人。
     */
    @Update("""
            UPDATE seckill_activity
            SET sold_count = sold_count - 1
            WHERE id = #{activityId}
              AND sold_count > 0
            """)
    int decreaseSoldCount(@Param("activityId") Long activityId);
}
