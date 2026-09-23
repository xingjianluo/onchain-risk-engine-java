package com.github.xingjianluo.riskengine.score;

import java.util.List;

/**
 * 钱包风险评分结果：得分 + 可解释依据链 + 规则版本。
 */
public record RiskScoreResult(
        String address,
        int score,
        int ruleSetVersion,
        List<String> evidence) {
}
