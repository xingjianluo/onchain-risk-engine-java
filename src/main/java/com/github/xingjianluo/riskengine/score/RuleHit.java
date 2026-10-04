package com.github.xingjianluo.riskengine.score;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一条结构化的规则命中记录，供自动对账/比对使用（人读版本仍在 evidence 中）。
 *
 * <p>所有集合字段在构造时按字典序排序并去重，金额合计基于归一化后的
 * {@code BigDecimal}，因此同一批事件无论以什么顺序到达、重复投递多少次，
 * 生成的 RuleHit 都逐字段一致。</p>
 *
 * @param rule            命中的规则：LARGE_AMOUNT / FAN_OUT / FAN_IN
 * @param direction       资金方向：OUTGOING / INCOMING / BOTH（大额命中可能双向）
 * @param counterparties  涉及的对手方地址（升序）
 * @param totalAmount     相关转账金额合计
 * @param eventIds        关联的交易事件 ID（升序）
 * @param txHashes        关联的链上交易哈希（升序）
 */
public record RuleHit(
        String rule,
        String direction,
        List<String> counterparties,
        BigDecimal totalAmount,
        List<String> eventIds,
        List<String> txHashes) {

    public static final String DIRECTION_OUTGOING = "OUTGOING";
    public static final String DIRECTION_INCOMING = "INCOMING";
    public static final String DIRECTION_BOTH = "BOTH";
}
