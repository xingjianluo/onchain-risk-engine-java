package com.github.xingjianluo.riskengine.score;

import java.util.List;

/**
 * 钱包风险评分结果。
 *
 * <p>兼容约定：前四个字段（address / score / ruleSetVersion / evidence）
 * 是既有契约，含义与格式保持不变；ruleHits 与 eventSetFingerprint 为新增字段，
 * 旧调用方不读取即不受任何影响。</p>
 *
 * @param address             钱包地址（小写归一化）
 * @param score               风险分（0-100）
 * @param ruleSetVersion      本次评分使用的规则集版本
 * @param evidence            人读的文字依据链（保持原有格式）
 * @param ruleHits            结构化判定依据：命中规则、资金方向、对手方、金额、关联交易
 * @param eventSetFingerprint 本次结论所依据事件集合的 SHA-256 指纹
 */
public record RiskScoreResult(
        String address,
        int score,
        int ruleSetVersion,
        List<String> evidence,
        List<RuleHit> ruleHits,
        String eventSetFingerprint) {
}
