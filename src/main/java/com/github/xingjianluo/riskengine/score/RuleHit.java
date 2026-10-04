package com.github.xingjianluo.riskengine.score;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一条结构化的规则命中记录：机器可读，用于自动对账与比对。
 *
 * <p>所有集合字段在构造时按确定性顺序给出（事件按 (timestamp, eventId) 排序、
 * 对手方按字典序排序），因此同一批事件无论以什么顺序到达、重复投递多少次，
 * 生成的 RuleHit 都逐字段一致。</p>
 *
 * @param rule               命中的规则编码：LARGE_AMOUNT / FAN_OUT / FAN_IN
 * @param direction          资金方向：OUTGOING / INCOMING
 * @param scoreContribution  本次命中贡献的加分
 * @param amount             相关金额：单笔命中为单笔金额；聚合命中（FAN_OUT/FAN_IN）为涉及金额合计
 * @param asset              资产代码（单笔命中给出；聚合命中可能涉及多资产，为 null）
 * @param counterparties     涉及的对手方地址（单笔命中为单元素列表）
 * @param eventIds           关联的事件 ID（确定性排序）
 * @param txHashes           关联的链上交易哈希（与 eventIds 同序）
 */
public record RuleHit(
        String rule,
        String direction,
        int scoreContribution,
        BigDecimal amount,
        String asset,
        List<String> counterparties,
        List<String> eventIds,
        List<String> txHashes) {
}
