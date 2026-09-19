package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.config.BlogWalletProperties;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.WalletRechargeOrder;
import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletPackageVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.WalletRechargeOrderMapper;
import com.hailin.blogsystem.service.WalletRechargeService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.OrderNoGenerator;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 充值订单服务实现（虚拟支付）。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §4
 *
 * <p><b>幂等靠什么</b>：订单号 + 状态 CAS（第二层）+ 流水唯一索引（第三层）。
 * <b>不引入 Idempotency-Key 存储</b>——项目现有的 {@code WorkflowActionIdempotency}
 * 是 Redis 的，而钱的操作不能依赖 Redis（Redis 挂了幂等就失效）。
 * 订单号在这个场景里本身就是天然的请求去重键，比再引入一层 key 存储更强。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WalletRechargeServiceImpl extends ServiceImpl<WalletRechargeOrderMapper, WalletRechargeOrder>
        implements WalletRechargeService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PAID = "PAID";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final WalletRechargeOrderMapper rechargeOrderMapper;
    private final WalletService walletService;
    private final BlogWalletProperties walletProperties;

    @Override
    public List<WalletPackageVO> listPackages() {
        return walletProperties.getPackages().entrySet().stream()
                .map(entry -> {
                    WalletPackageVO vo = new WalletPackageVO();
                    vo.setCode(entry.getKey());
                    vo.setPayAmount(entry.getValue().getPayAmount());
                    vo.setCreditAmount(entry.getValue().getCreditAmount());
                    return vo;
                })
                .toList();
    }

    @Override
    public RechargeOrderVO createOrder(String packageCode) {
        Long userId = requireLogin();

        BlogWalletProperties.Package pkg = walletProperties.getPackages().get(packageCode);
        if (pkg == null) {
            throw new IllegalArgumentException("套餐不存在：" + packageCode);
        }

        WalletRechargeOrder order = new WalletRechargeOrder();
        order.setOrderNo(OrderNoGenerator.next("W"));
        order.setUserId(userId);
        order.setPackageCode(packageCode);
        // 金额与到账额度从套餐快照写入——请求体里根本没有金额字段，前端说了不算
        order.setPayAmount(pkg.getPayAmount());
        order.setCreditAmount(pkg.getCreditAmount());
        order.setStatus(STATUS_PENDING);
        order.setVersion(0);
        save(order);

        log.info("[RECHARGE] 下单 user_id={} order_no={} package={} credit={}",
                userId, order.getOrderNo(), packageCode, pkg.getCreditAmount());
        return RechargeOrderVO.from(order);
    }

    @Override
    @Transactional
    public RechargeOrderVO pay(String orderNo) {
        Long userId = requireLogin();

        WalletRechargeOrder order = lambdaQuery()
                .eq(WalletRechargeOrder::getOrderNo, orderNo)
                .one();
        if (order == null) {
            throw new BusinessException(BlogConstants.ErrorCode.NOT_FOUND, "订单不存在");
        }
        // 归属校验不能省：orderNo 是可枚举的短字符串，不校验等于把别人的订单
        // 交给任何人「代付」——付完余额加到别人账上。
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(BlogConstants.ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        if (STATUS_CANCELLED.equals(order.getStatus())) {
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT, "订单已取消，无法支付");
        }
        if (STATUS_PAID.equals(order.getStatus())) {
            // 已经付过了：幂等成功，不能再加一次余额
            return RechargeOrderVO.from(order);
        }

        // 状态 CAS：并发双击时只有一个事务能推进状态
        if (rechargeOrderMapper.markPaid(orderNo) == 0) {
            // 影响 0 行必须重新确认状态，绝不能继续加余额。
            // 这里用锁定读而不是普通 SELECT：MySQL 默认 REPEATABLE READ 下，
            // 普通读走的是事务开始时的快照，可能还停在 PENDING，据此判断会得出错误结论。
            WalletRechargeOrder latest = rechargeOrderMapper.selectByOrderNoForUpdate(orderNo);
            if (latest != null && STATUS_PAID.equals(latest.getStatus())) {
                return RechargeOrderVO.from(latest);
            }
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT, "订单状态已变更，请刷新后重试");
        }

        return completePaidOrder(orderNo);
    }

    /**
     * 加余额 + 写流水。
     *
     * <p>与 {@link #pay} 的自调用共享同一个事务（Spring 自调用不走代理），
     * 所以「CAS 置 PAID」和「加余额」要么一起成功、要么一起回滚。
     */
    @Override
    @Transactional
    public RechargeOrderVO completePaidOrder(String orderNo) {
        WalletRechargeOrder order = lambdaQuery()
                .eq(WalletRechargeOrder::getOrderNo, orderNo)
                .one();
        if (order == null) {
            throw new BusinessException(BlogConstants.ErrorCode.NOT_FOUND, "订单不存在");
        }

        // 记账幂等键由订单号派生：即使订单被推进两次，第二笔流水也会撞 uk_user_idem 而回滚，
        // 不会出现「加了两次钱」。这是三层防线里的最后一层。
        walletService.increase(
                order.getUserId(),
                order.getCreditAmount(),
                "RECHARGE",
                "RECHARGE_ORDER",
                orderNo,
                "RECHARGE:" + orderNo,
                "充值套餐 " + order.getPackageCode());

        log.info("[RECHARGE] 支付完成 user_id={} order_no={} credit=+{}",
                order.getUserId(), orderNo, order.getCreditAmount());
        return RechargeOrderVO.from(order);
    }

    private Long requireLogin() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return userId;
    }
}
