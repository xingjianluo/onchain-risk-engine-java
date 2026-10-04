package com.github.xingjianluo.riskengine.model;

/**
 * 事件被拒绝时的可区分失败原因。
 */
public enum RejectionReason {
    /** 同一个 eventId 被重复投递。 */
    DUPLICATE_EVENT,
    /** eventId 是新的，但链上交易哈希已被其他事件计价过（同一笔交易换了投递编号）。 */
    DUPLICATE_TRANSACTION,
    MISSING_EVENT_ID,
    MISSING_TX_HASH,
    INVALID_FROM_ADDRESS,
    INVALID_TO_ADDRESS,
    MISSING_AMOUNT,
    NON_POSITIVE_AMOUNT,
    AMOUNT_OUT_OF_RANGE,
    MISSING_ASSET,
    MISSING_TIMESTAMP,
    TIMESTAMP_OUT_OF_RANGE
}
