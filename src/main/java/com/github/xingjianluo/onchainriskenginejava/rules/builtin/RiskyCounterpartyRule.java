package com.github.xingjianluo.onchainriskenginejava.rules.builtin;

import com.github.xingjianluo.onchainriskenginejava.engine.WalletGraph;
import com.github.xingjianluo.onchainriskenginejava.model.Evidence;
import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;
import com.github.xingjianluo.onchainriskenginejava.rules.RiskRule;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * R003 高风险对手：与风险名单内地址发生直接资金往来（转入或转出）时命中。
 * 名单是不可变快照，规则评估结果只取决于图数据与该名单。
 *
 * @param riskyAddresses 不可变风险地址集合（小写）
 * @param points         命中分值（每次命中只计一次分，按对手去重）
 * @param ruleVersion    规则定义版本
 */
public record RiskyCounterpartyRule(Set<String> riskyAddresses, int points, String ruleVersion)
        implements RiskRule {

    public static final String CODE = "R003_RISKY_COUNTERPARTY";
    public static final String NAME = "高风险对手往来";

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
        Set<String> hits = new TreeSet<>();
        List<Map<String, Object>> txFacts = new java.util.ArrayList<>();

        for (TransactionEvent e : graph.outgoing(address)) {
            if (riskyAddresses.contains(e.to())) {
                hits.add(e.to());
                txFacts.add(txFact("OUT", e));
            }
        }
        for (TransactionEvent e : graph.incoming(address)) {
            if (riskyAddresses.contains(e.from())) {
                hits.add(e.from());
                txFacts.add(txFact("IN", e));
            }
        }
        if (hits.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> facts = new TreeMap<>();
        facts.put("riskyCounterparties", List.copyOf(hits));
        facts.put("riskyCounterpartyCount", hits.size());
        facts.put("relatedTransactions", txFacts);
        return Optional.of(new Evidence(CODE, NAME, points,
                "与 " + hits.size() + " 个风险名单地址发生直接资金往来: " + String.join(", ", hits),
                facts));
    }

    private static Map<String, Object> txFact(String direction, TransactionEvent e) {
        Map<String, Object> m = new TreeMap<>();
        m.put("direction", direction);
        m.put("txHash", e.txHash());
        m.put("counterparty", "OUT".equals(direction) ? e.to() : e.from());
        m.put("amount", e.amount().toPlainString());
        m.put("timestamp", e.timestamp());
        return m;
    }
}
