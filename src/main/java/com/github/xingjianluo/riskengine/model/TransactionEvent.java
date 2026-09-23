package com.github.xingjianluo.riskengine.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 链上转账事件（本地模拟事件流中的一条记录）。
 */
public record TransactionEvent(
        String eventId,
        String txHash,
        String fromAddress,
        String toAddress,
        BigDecimal amount,
        String asset,
        Instant timestamp) {
}
