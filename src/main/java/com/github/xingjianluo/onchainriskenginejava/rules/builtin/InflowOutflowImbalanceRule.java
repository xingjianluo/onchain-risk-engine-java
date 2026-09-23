package com.github.xingjianluo.onchainriskenginejava.rules.builtin;

import com.github.xingjianluo.onchainriskenginejava.engine.WalletGraph;
import com.github.xingjianluo.onchainriskenginejava.model.Evidence;
import com.github.xingjianluo.onchainriskenginejava.rules.RiskRule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * R005 流入流出失衡：累计流入达到门槛，但累计流出不足流入的给定比例，
 * 即资金“只进不出”，符合归集沉淀型地址特征。
 *
 * @param minInflow      触发评估的最小累计流入
 * @param maxOutRatio    允许的最大 流出/流入 比例（低于即命中）
 * @param points         命中分值
 * @param ruleVersion    规则定义版本
 */
public record InflowOutflowImbalanceRule(BigDecimal minInflow,
                                         BigDecimal maxOutRatio,
                                         int points,
                                         String ruleVersion)
        implements RiskRule {

    public static final String CODE = "R005_INFLOW_OUTFLOW_IMBALANCE";
    public static final String NAME = "流入流出失衡";

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
        BigDecimal totalIn = graph.totalIn(address);
        if (totalIn.compareTo(minInflow) < 0) {
            return Optional.empty();
        }
        BigDecimal totalOut = graph.totalOut(address);
        BigDecimal ratio = totalOut.divide(totalIn, 6, RoundingMode.HALF_UP);
        if (ratio.compareTo(maxOutRatio) >= 0) {
            return Optional.empty();
        }
        Map<String, Object> facts = new TreeMap<>();
        facts.put("totalIn", totalIn.toPlainString());
        facts.put("totalOut", totalOut.toPlainString());
        facts.put("outInRatio", ratio.toPlainString());
        facts.put("maxOutRatio", maxOutRatio.toPlainString());
        facts.put("minInflow", minInflow.toPlainString());
        return Optional.of(new Evidence(CODE, NAME, points,
                "累计流入 " + totalIn.toPlainString() + " 而流出仅占 " + ratio.toPlainString()
                        + "（阈值 " + maxOutRatio.toPlainString() + "），资金沉淀", facts));
    }
}
