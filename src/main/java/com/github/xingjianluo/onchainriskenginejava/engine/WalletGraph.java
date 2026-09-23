package com.github.xingjianluo.onchainriskenginejava.engine;

import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 基于已接受事件构造的不可变钱包资金流向图。
 *
 * <p><b>顺序无关性</b>：构造时先把全部事件按确定性主键
 * {@code (timestamp, txHash, from, to)} 升序排序（金额、资产作为最终兜底比较项），
 * 因此无论事件以何种顺序、是否乱序 / 并发摄入，同一批数据永远构造出逐字节相同的图，
 * 进而保证评分一致、可复现。</p>
 */
public final class WalletGraph {

    /** 规范化事件排序键：时间 -> 交易哈希 -> 付款方 -> 收款方 -> 金额 -> 资产。 */
    private static final Comparator<TransactionEvent> CANONICAL = Comparator
            .comparingLong(TransactionEvent::timestamp)
            .thenComparing(TransactionEvent::txHash)
            .thenComparing(TransactionEvent::from)
            .thenComparing(TransactionEvent::to)
            .thenComparing(TransactionEvent::amount)
            .thenComparing(TransactionEvent::asset, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final List<TransactionEvent> canonicalEvents;
    private final Map<String, List<TransactionEvent>> outgoingByAddress;
    private final Map<String, List<TransactionEvent>> incomingByAddress;
    private final List<String> allAddresses;

    private WalletGraph(List<TransactionEvent> canonicalEvents,
                        Map<String, List<TransactionEvent>> outgoingByAddress,
                        Map<String, List<TransactionEvent>> incomingByAddress,
                        List<String> allAddresses) {
        this.canonicalEvents = canonicalEvents;
        this.outgoingByAddress = outgoingByAddress;
        this.incomingByAddress = incomingByAddress;
        this.allAddresses = allAddresses;
    }

    public static WalletGraph from(List<TransactionEvent> events) {
        List<TransactionEvent> sorted = new ArrayList<>(events);
        sorted.sort(CANONICAL);
        List<TransactionEvent> canonical = List.copyOf(sorted);

        Set<String> addresses = new TreeSet<>();
        Map<String, List<TransactionEvent>> out = new HashMap<>();
        Map<String, List<TransactionEvent>> in = new HashMap<>();
        for (TransactionEvent e : canonical) {
            addresses.add(e.from());
            addresses.add(e.to());
            out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
            in.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
        }
        // 全部明细列表冻结为不可变
        Map<String, List<TransactionEvent>> outImmutable = freeze(out);
        Map<String, List<TransactionEvent>> inImmutable = freeze(in);
        return new WalletGraph(canonical,
                Collections.unmodifiableMap(outImmutable),
                Collections.unmodifiableMap(inImmutable),
                List.copyOf(addresses));
    }

    private static Map<String, List<TransactionEvent>> freeze(Map<String, List<TransactionEvent>> src) {
        Map<String, List<TransactionEvent>> dst = new LinkedHashMap<>();
        src.forEach((k, v) -> dst.put(k, List.copyOf(v)));
        return dst;
    }

    /** 规范化后的全部事件。 */
    public List<TransactionEvent> canonicalEvents() {
        return canonicalEvents;
    }

    /** 该钱包转出总额。 */
    public BigDecimal totalOut(String address) {
        return sum(outgoing(address));
    }

    /** 该钱包转入总额。 */
    public BigDecimal totalIn(String address) {
        return sum(incoming(address));
    }

    /** 该钱包转出的不重复对手数量。 */
    public int distinctOutgoingCounterparties(String address) {
        Set<String> counterparties = new TreeSet<>();
        for (TransactionEvent e : outgoing(address)) {
            counterparties.add(e.to());
        }
        return counterparties.size();
    }

    /** 该钱包的全部交易对手（排序、不重复）。 */
    public Set<String> counterparties(String address) {
        Set<String> counterparties = new TreeSet<>();
        for (TransactionEvent e : outgoing(address)) {
            counterparties.add(e.to());
        }
        for (TransactionEvent e : incoming(address)) {
            counterparties.add(e.from());
        }
        return counterparties;
    }

    /** 该钱包的转出明细（按规范化顺序）。 */
    public List<TransactionEvent> outgoing(String address) {
        return outgoingByAddress.getOrDefault(address, List.of());
    }

    /** 该钱包的转入明细（按规范化顺序）。 */
    public List<TransactionEvent> incoming(String address) {
        return incomingByAddress.getOrDefault(address, List.of());
    }

    /** 图中出现过的全部钱包地址（字典序）。 */
    public List<String> allAddresses() {
        return allAddresses;
    }

    private static BigDecimal sum(List<TransactionEvent> events) {
        BigDecimal total = BigDecimal.ZERO;
        for (TransactionEvent e : events) {
            total = total.add(e.amount());
        }
        return total;
    }
}
