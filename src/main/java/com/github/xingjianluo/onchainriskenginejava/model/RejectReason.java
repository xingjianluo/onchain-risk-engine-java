package com.github.xingjianluo.onchainriskenginejava.model;

/**
 * 事件被拒绝的可区分原因。每一种非法输入 / 重复投递都有独立原因码，
 * 不做静默丢弃。
 */
public enum RejectReason {

    MISSING_FIELD("缺失必填字段"),
    INVALID_ADDRESS("地址格式非法（要求 0x + 40 位十六进制）"),
    SELF_TRANSACTION("付款地址与收款地址相同"),
    AMOUNT_OUT_OF_RANGE("金额超出允许范围（必须为正数且不超过上限）"),
    TIMESTAMP_OUT_OF_RANGE("时间戳超出允许范围"),
    DUPLICATE_EVENT_ID("eventId 重复投递"),
    DUPLICATE_TX_HASH("txHash 与已接受交易重复");

    private final String defaultMessage;

    RejectReason(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
