package com.hailin.blogsystem.entity.vo;

import lombok.Data;

/**
 * 钱包查询结果。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §3「接口要能传达这个区分」
 *
 * <p><b>为什么不能只返回 balance</b>：余额为负有两种完全不同的含义，
 * 只看一个数字会误报——用户一发起调用就看到负余额，以为已经欠款了。
 *
 * <ul>
 *   <li><b>预占中</b>（{@code pendingReserveCount > 0}）：额度被临时占用，
 *       调用结束后按实际用量退回来，<b>不是欠款</b>，不该提示充值</li>
 *   <li><b>结算后仍为负</b>（{@code pendingReserveCount == 0}）：真的补不上，需要充值</li>
 * </ul>
 */
@Data
public class WalletVO {

    /** 当前余额（单位 credit），可能为负 */
    private Long balance;

    /** 进行中的预扣笔数（ai_billing_order.status = RESERVED） */
    private Long pendingReserveCount;
}
