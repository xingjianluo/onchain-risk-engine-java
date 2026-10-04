package com.github.xingjianluo.riskengine.score;

import java.util.List;

/**
 * 钱包风险评分结果。
 *
 * <p>前四个字段（address / score / ruleSetVersion / evidence）为既有返回内容，
 * 保持不变；ruleHits 与 eventSetFingerprint 是新增的结构化判定依据，
 * 供自动对账使用，不影响只消费旧字段的调用方。</p>
 *
 * @param address             钱包地址（小写归一化）
 * @param score               总分（封顶 100）
 * @param ruleSetVersion      本次评分使用的规则集版本
 * @param evidence            人读的判定依据链（逐条文字）
 * @param ruleHits            结构化的规则命中记录（规则、方向、对手方、金额、关联交易）
 * @param eventSetFingerprint 本次评分所依据事件集合的 SHA-256 指纹
 */
public record RiskScoreResult(
        String address,
        int score,
        int ruleSetVersion,
        List<String> evidence,
        List<RuleHit> ruleHits,
        String eventSetFingerprint) {
}
