package com.github.xingjianluo.riskengine.model;

/**
 * 事件被拒绝时的可区分失败原因。
 */
public enum RejectionReason {
    DUPLICATE_EVENT,
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
