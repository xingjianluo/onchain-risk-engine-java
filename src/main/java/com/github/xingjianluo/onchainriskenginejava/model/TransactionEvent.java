package com.github.xingjianluo.onchainriskenginejava.model;

import java.math.BigDecimal;

/**
 * 一笔链上交易事件（本地模拟事件流的最小载荷）。
 *
 * @param eventId   事件投递唯一 ID，用于幂等判重
 * @param txHash    链上交易哈希，同一笔交易的业务唯一键
 * @param timestamp 交易发生时间（epoch millis）
 * @param from      付款地址
 * @param to        收款地址
 * @param amount    金额（正数）
 * @param asset     资产符号，如 ETH
 */
public record TransactionEvent(
        String eventId,
        String txHash,
        long timestamp,
        String from,
        String to,
        BigDecimal amount,
        String asset
) {
}
