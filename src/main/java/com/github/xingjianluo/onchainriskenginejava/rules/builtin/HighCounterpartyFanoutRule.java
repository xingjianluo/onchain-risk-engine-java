package com.github.xingjianluo.onchainriskenginejava.rules.builtin;

import com.github.xingjianluo.onchainriskenginejava.engine.WalletGraph;
import com.github.xingjianluo.onchainriskenginejava.model.Evidence;
import com.github.xingjianluo.onchainriskenginejava.rules.RiskRule;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * R002 交易对手扩散：钱包向外转账的不重复对手数量达到阈值时命中。
 * 对手数量越多，越像归集 / 撒钱型地址。
 *
 * @param minCounterparties 不重复转出对手阈值（含）
 * @param points            命中分值
 * @param ruleVersion       规则定义版本
 */
public record HighCounterpartyFanoutRule(int minCounterparties, int points, String ruleVersion)
        implements RiskRule {

    public static final String CODE = "R002_COUNTERPARTY_FANOUT";
    public static final String NAME = "交易对手扩散";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String version() {
        return ruleVersion;
    }

    @Override
    public Optional<Evidence> evaluate(String address, WalletGraph graph) {
        int distinct = graph.distinctOutgoingCounterparties(address);
        if (distinct < minCounterparties) {
            return Optional.empty();
        }
        Map<String, Object> facts = new TreeMap<>();
        facts.put("distinctOutgoingCounterparties", distinct);
        facts.put("threshold", minCounterparties);
        facts.put("counterparties", graph.counterparties(address).stream().sorted().toList());
        return Optional.of(new Evidence(CODE, NAME, points,
                "向 " + distinct + " 个不重复对手转出，达到扩散阈值 " + minCounterparties,
                facts));
    }
}
