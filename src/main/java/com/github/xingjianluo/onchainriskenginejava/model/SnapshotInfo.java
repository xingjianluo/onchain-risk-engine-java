package com.github.xingjianluo.onchainriskenginejava.model;

/**
 * 一次评分 / 告警查询所基于的一致性快照标识。
 *
 * @param ruleSetVersion 使用的规则集版本
 * @param eventCount     快照内事件数量
 * @param eventsHash     快照事件规范化后的 SHA-256，用于复现与审计
 */
public record SnapshotInfo(
        String ruleSetVersion,
        int eventCount,
        String eventsHash
) {
}
