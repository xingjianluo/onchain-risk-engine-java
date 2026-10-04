package com.github.xingjianluo.riskengine.model;

/**
 * 事件被拒绝时的可区分失败原因。
 */
public enum RejectionReason {
    /** 投递编号（eventId）重复：同一条投递被重复发送。 */
    DUPLICATE_EVENT,
    /** 交易本身重复：eventId 不同，但链上交易哈希已被其他事件计价过。 */
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
