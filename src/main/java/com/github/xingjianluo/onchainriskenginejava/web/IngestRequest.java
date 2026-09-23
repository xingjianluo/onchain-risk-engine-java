package com.github.xingjianluo.onchainriskenginejava.web;

import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;

import java.math.BigDecimal;

/**
 * 摄入接口请求体（字段与 TransactionEvent 对齐，方便 Jackson 绑定与缺失字段检测）。
 */
public record IngestRequest(
        String eventId,
        String txHash,
        Long timestamp,
        String from,
        String to,
        BigDecimal amount,
        String asset
) {
    public TransactionEvent toEvent() {
        return new TransactionEvent(eventId, txHash, timestamp == null ? 0L : timestamp, from, to, amount, asset);
    }
}
