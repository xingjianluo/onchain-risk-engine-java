package com.github.xingjianluo.riskengine.model;

/**
 * 单条事件摄入结果：要么被接受，要么携带可区分的拒绝原因。
 */
public record IngestResult(boolean accepted, RejectionReason rejectionReason, String detail) {

    public static IngestResult ok() {
        return new IngestResult(true, null, "ACCEPTED");
    }

    public static IngestResult rejected(RejectionReason reason, String detail) {
        return new IngestResult(false, reason, detail);
    }
}
