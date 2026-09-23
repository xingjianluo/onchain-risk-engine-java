package com.github.xingjianluo.onchainriskenginejava.model;

import java.util.List;

/**
 * 达到阈值的风险告警。
 */
public record RiskAlert(
        String address,
        int score,
        int threshold,
        List<String> triggeredRuleCodes,
        SnapshotInfo snapshot
) {
}
