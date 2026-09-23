package com.github.xingjianluo.onchainriskenginejava.model;

/**
 * 单个事件的摄入结果。重复 / 非法事件以 accepted=false + 明确原因返回，
 * 绝不静默丢弃。
 */
public record IngestionResult(
        boolean accepted,
        RejectReason reason,
        String message,
        TransactionEvent event
) {
    public static IngestionResult accepted(TransactionEvent event) {
        return new IngestionResult(true, null, "ACCEPTED", event);
    }

    public static IngestionResult rejected(RejectReason reason, String message, TransactionEvent event) {
        return new IngestionResult(false, reason, message, event);
    }
}
