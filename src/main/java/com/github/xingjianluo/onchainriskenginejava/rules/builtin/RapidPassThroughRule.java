package com.github.xingjianluo.onchainriskenginejava.rules.builtin;

import com.github.xingjianluo.onchainriskenginejava.engine.WalletGraph;
import com.github.xingjianluo.onchainriskenginejava.model.Evidence;
import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;
import com.github.xingjianluo.onchainriskenginejava.rules.RiskRule;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * R004 快速过账：一笔转入之后的指定时间窗口内，出现金额不小于该笔转入
 * {@code minOutRatio} 比例的转出（对手不是付款来源方），视为资金快速中转，
 * 是混币 / 归集打散链路的典型特征。
 *
 * <p>事件列表在 {@link WalletGraph} 中已按时间规范化，且判定只依赖相对时序，
 * 因此对乱序到达天然稳健。</p>
 *
 * @param windowMillis 过账时间窗口（毫秒）
 * @param minOutRatio  转出金额 / 触发转入金额 的最小比例（0,1]
 * @param points       命中分值
 * @param ruleVersion  规则定义版本
 */
public record RapidPassThroughRule(long windowMillis,
                                   java.math.BigDecimal minOutRatio,
                                   int points,
                                   String ruleVersion)
        implements RiskRule {

    public static final String CODE = "R004_RAPID_PASS_THROUGH";
    public static final String NAME = "快速过账";

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
        List<TransactionEvent> incoming = graph.incoming(address);
        List<TransactionEvent> outgoing = graph.outgoing(address);
        List<Map<String, Object>> chains = new ArrayList<>();

        for (TransactionEvent in : incoming) {
            for (TransactionEvent out : outgoing) {
                long delta = out.timestamp() - in.timestamp();
                if (delta < 0L || delta > windowMillis) {
                    continue;
                }
                if (out.to().equals(in.from())) {
                    // 原路退回 / 同对手轧差不计为过账
                    continue;
                }
                BigDecimal ratio = out.amount().divide(in.amount(), 6, java.math.RoundingMode.HALF_UP);
                if (ratio.compareTo(minOutRatio) < 0) {
                    continue;
                }
                Map<String, Object> chain = new TreeMap<>();
                chain.put("inTxHash", in.txHash());
                chain.put("inFrom", in.from());
                chain.put("inAmount", in.amount().toPlainString());
                chain.put("inTimestamp", in.timestamp());
                chain.put("outTxHash", out.txHash());
                chain.put("outTo", out.to());
                chain.put("outAmount", out.amount().toPlainString());
                chain.put("outTimestamp", out.timestamp());
                chain.put("elapsedMillis", delta);
                chain.put("outInRatio", ratio.toPlainString());
                chains.add(Map.copyOf(chain));
            }
        }
        if (chains.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> facts = new TreeMap<>();
        facts.put("windowMillis", windowMillis);
        facts.put("minOutRatio", minOutRatio.toPlainString());
        facts.put("passThroughChainCount", chains.size());
        facts.put("chains", chains);
        return Optional.of(new Evidence(CODE, NAME, points,
                "发现 " + chains.size() + " 条 " + windowMillis + "ms 内的快速过账链路", facts));
    }
}
