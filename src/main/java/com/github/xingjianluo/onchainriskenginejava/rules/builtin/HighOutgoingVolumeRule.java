package com.github.xingjianluo.onchainriskenginejava.rules.builtin;

import com.github.xingjianluo.onchainriskenginejava.engine.WalletGraph;
import com.github.xingjianluo.onchainriskenginejava.model.Evidence;
import com.github.xingjianluo.onchainriskenginejava.rules.RiskRule;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * R001 大额转出：钱包累计转出额达到阈值时命中。
 *
 * @param thresholdAmount 累计转出阈值（含）
 * @param points          命中分值
 * @param ruleVersion     规则定义版本
 */
public record HighOutgoingVolumeRule(BigDecimal thresholdAmount, int points, String ruleVersion)
        implements RiskRule {

    public static final String CODE = "R001_HIGH_OUTGOING_VOLUME";
    public static final String NAME = "大额转出";

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
        BigDecimal totalOut = graph.totalOut(address);
        if (totalOut.compareTo(thresholdAmount) < 0) {
            return Optional.empty();
        }
        Map<String, Object> facts = new TreeMap<>();
        facts.put("totalOut", totalOut.toPlainString());
        facts.put("threshold", thresholdAmount.toPlainString());
        facts.put("outgoingCount", graph.outgoing(address).size());
        return Optional.of(new Evidence(CODE, NAME, points,
                "累计转出 " + totalOut.toPlainString() + " 达到阈值 " + thresholdAmount.toPlainString(),
                facts));
    }
}
