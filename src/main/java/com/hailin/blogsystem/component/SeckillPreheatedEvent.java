package com.hailin.blogsystem.component;

/**
 * 秒杀活动预热完成。
 *
 * <p>存在的理由：{@code SeckillSettleTask} 把 ACTIVE 活动列表缓存了 60 秒
 * （每 2 秒查一次 DB 会把日志刷爆），于是**预热完的活动最多要等 60 秒才会被消费**
 * ——用户抢到了却一直显示「排队中」。
 *
 * <p>而预热是活动的必经路径（没预热的话抢购脚本会直接返回「活动暂未开放」），
 * 所以在这里发一个事件就能把这个窗口彻底消掉，不必缩短缓存时间、也就不必牺牲日志可读性。
 */
public record SeckillPreheatedEvent() {
}
