package com.github.xingjianluo.onchainriskenginejava.model;

import java.util.List;

/**
 * 钱包风险评分结论。score 为 0..100 封顶后的最终分，rawPoints 为规则原始累加分，
 * evidences 为按规则码排序的依据链。
 */
public record RiskScore(
        String address,
        int rawPoints,
        int score,
        List<Evidence> evidences,
        SnapshotInfo snapshot
) {
}
