package com.hailin.blogsystem.utils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 业务单号生成：前缀 + 时间戳 + 6 位随机码，例如 {@code W20260917153012A3F9K2}。
 *
 * <p><b>为什么不用雪花 ID</b>：订单号要暴露给前端。雪花是连号的数字，
 * 既暴露发单时间与机器信息，又让人能顺着号段去遍历别人的订单。
 * 归属校验当然一处都不会省（见 {@code WalletRechargeServiceImpl#pay}），
 * 但订单号本身不该是这套体系的唯一防线——少给攻击者一个顺手的入口。
 *
 * <p>随机码去掉了易混字符（I / O / 0 / 1）：用户可能要把单号念出来或手抄。
 */
public final class OrderNoGenerator {

    /** 去掉 I / O / 0 / 1，避免口头传达或手抄时认错 */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int RANDOM_LENGTH = 6;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private OrderNoGenerator() {
    }

    /**
     * @param prefix 业务前缀：{@code W} = 充值订单，{@code B} = AI 计费单
     */
    public static String next(String prefix) {
        StringBuilder sb = new StringBuilder(prefix).append(LocalDateTime.now().format(TS));
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
