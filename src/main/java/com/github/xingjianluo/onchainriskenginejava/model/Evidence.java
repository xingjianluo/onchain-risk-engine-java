package com.github.xingjianluo.onchainriskenginejava.model;

import java.util.Map;

/**
 * 单条风险规则命中后的可解释依据。
 *
 * @param ruleCode  规则码
 * @param ruleName  规则名
 * @param points    本规则贡献的分值
 * @param rationale 人可读的判定理由
 * @param facts     支撑判定的事实数据（确定性、可序列化）
 */
public record Evidence(
        String ruleCode,
        String ruleName,
        int points,
        String rationale,
        Map<String, Object> facts
) {
}
