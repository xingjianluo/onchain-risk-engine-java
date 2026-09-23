package com.github.xingjianluo.onchainriskenginejava;

import com.github.xingjianluo.onchainriskenginejava.engine.ScoringEngine;
import com.github.xingjianluo.onchainriskenginejava.engine.WalletGraph;
import com.github.xingjianluo.onchainriskenginejava.model.Evidence;
import com.github.xingjianluo.onchainriskenginejava.model.RiskScore;
import com.github.xingjianluo.onchainriskenginejava.model.SnapshotInfo;
import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;
import com.github.xingjianluo.onchainriskenginejava.rules.RiskRuleSet;
import com.github.xingjianluo.onchainriskenginejava.rules.builtin.BuiltInRules;
import com.github.xingjianluo.onchainriskenginejava.rules.builtin.RapidPassThroughRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 评分确定性：同一批数据在任意摄入顺序（含随机乱序、反转）下，
 * 构造出的图、评分与依据链必须逐字段一致。
 */
class ScoringDeterminismTest {

    private static final Logger log = LoggerFactory.getLogger(ScoringDeterminismTest.class);

    private static final String A = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String C = "0xcccccccccccccccccccccccccccccccccccccccc";
    private static final String D = "0xdddddddddddddddddddddddddddddddddddddddd";
    private static final String E = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String DARK = "0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead";

    private final ScoringEngine engine = new ScoringEngine();
    private final RiskRuleSet ruleSet = BuiltInRules.v1(Set.of(DARK));
    private final SnapshotInfo snapshot = new SnapshotInfo(ruleSet.version(), 0, "test-hash");

    private List<TransactionEvent> sampleData() {
        long t = 1_700_000_000_000L;
        List<TransactionEvent> events = new ArrayList<>();
        events.add(tx("t1", t, B, A, "100"));
        events.add(tx("t2", t + 5 * 60_000L, A, C, "20"));
        events.add(tx("t3", t + 10 * 60_000L, A, D, "20"));
        events.add(tx("t4", t + 20 * 60_000L, A, E, "20"));
        events.add(tx("t5", t + 30 * 60_000L, A, "0x1212121212121212121212121212121212121212", "20"));
        events.add(tx("t6", t + 40 * 60_000L, A, "0x3434343434343434343434343434343434343434", "20"));
        events.add(tx("t7", t + 50 * 60_000L, A, DARK, "20"));
        return events;
    }

    private TransactionEvent tx(String h, long ts, String from, String to, String amount) {
        return new TransactionEvent("e-" + h, h, ts, from, to, new BigDecimal(amount), "ETH");
    }

    @Test
    @DisplayName("任意乱序摄入，评分与依据链完全一致")
    void scoreStableUnderAnyOrder() {
        List<TransactionEvent> base = sampleData();
        RiskScore ordered = score(base);

        List<TransactionEvent> reversed = new ArrayList<>(base);
        Collections.reverse(reversed);
        RiskScore reversedScore = score(reversed);

        Random random = new Random(42);
        List<TransactionEvent> shuffled = new ArrayList<>(base);
        Collections.shuffle(shuffled, random);
        RiskScore shuffledScore = score(shuffled);

        log.info("顺序评分   A => score={} raw={} hits={}",
                ordered.score(), ordered.rawPoints(), codes(ordered));
        log.info("反转评分   A => score={} raw={} hits={}",
                reversedScore.score(), reversedScore.rawPoints(), codes(reversedScore));
        log.info("随机乱序评分 A => score={} raw={} hits={}",
                shuffledScore.score(), shuffledScore.rawPoints(), codes(shuffledScore));

        assertEquals(ordered, reversedScore, "反转顺序必须得到相同结论");
        assertEquals(ordered, shuffledScore, "随机乱序必须得到相同结论");
        ordered.evidences().forEach(ev -> log.info(
                "依据 {} [{}分] {} facts={}", ev.ruleCode(), ev.points(), ev.rationale(), ev.facts()));

        // A 累计转出 120：R001(30) + 6 个对手 R002(20) + DARK R003(25)；
        // R004 要求 转出/转入>=0.8（此处仅 0.2）不命中 => 合计 75
        assertEquals(75, ordered.score());
        assertEquals(List.of("R001_HIGH_OUTGOING_VOLUME", "R002_COUNTERPARTY_FANOUT",
                "R003_RISKY_COUNTERPARTY"),
                ordered.evidences().stream().map(Evidence::ruleCode).toList());
    }

    @Test
    @DisplayName("规则参数版本变化时结论不同，但同一规则集永远稳定")
    void ruleSetVersionChangesResult() {
        List<TransactionEvent> data = sampleData();
        // 将 A 的六笔转出都改为 15（总额 90）：v1 阈值 100 不命中 R001，v2 阈值 80 命中
        long t = 1_700_000_000_000L;
        List<TransactionEvent> trimmed = new ArrayList<>(List.of(
                tx("t1", t, B, A, "100"),
                tx("t2", t + 5 * 60_000L, A, C, "15"),
                tx("t3", t + 10 * 60_000L, A, D, "15"),
                tx("t4", t + 20 * 60_000L, A, E, "15"),
                tx("t5", t + 40 * 60_000L, A, "0x1212121212121212121212121212121212121212", "15"),
                tx("t6", t + 40 * 60_000L, A, "0x3434343434343434343434343434343434343434", "15"),
                tx("t7", t + 50 * 60_000L, A, DARK, "15")));

        RiskScore v1 = scoreWith(trimmed, BuiltInRules.v1(Set.of(DARK)));
        RiskScore v2 = scoreWith(trimmed, BuiltInRules.v2(Set.of(DARK)));
        log.info("v1 评分={} hits={} ; v2 评分={} hits={}",
                v1.score(), codes(v1), v2.score(), codes(v2));

        assertFalse(v1.evidences().stream().anyMatch(e -> e.ruleCode().equals("R001_HIGH_OUTGOING_VOLUME")));
        assertTrue(v2.evidences().stream().anyMatch(e -> e.ruleCode().equals("R001_HIGH_OUTGOING_VOLUME")));
        assertNotEquals(v1.score(), v2.score());
        assertEquals(BuiltInRules.VERSION_V1, v1.snapshot().ruleSetVersion());
    }

    @Test
    @DisplayName("规则是纯函数：窗口外的转出不算快速过账")
    void rapidPassThroughRespectsWindow() {
        long t = 1_700_000_000_000L;
        List<TransactionEvent> events = List.of(
                tx("in", t, B, A, "100"),
                tx("out", t + 61 * 60_000L, A, C, "100"));
        WalletGraph graph = WalletGraph.from(events);
        var rule = new RapidPassThroughRule(60L * 60_000, new BigDecimal("0.8"), 15, "test");
        assertTrue(rule.evaluate(A, graph).isEmpty());
        var within = new RapidPassThroughRule(120L * 60_000, new BigDecimal("0.8"), 15, "test");
        assertTrue(within.evaluate(A, graph).isPresent());
        log.info("快速过账窗口边界判定符合预期");
    }

    private RiskScore score(List<TransactionEvent> events) {
        return scoreWith(events, ruleSet);
    }

    private RiskScore scoreWith(List<TransactionEvent> events, RiskRuleSet rs) {
        WalletGraph graph = WalletGraph.from(events);
        return engine.score(A, graph, rs, new SnapshotInfo(rs.version(), events.size(), "h"));
    }

    private List<String> codes(RiskScore s) {
        return s.evidences().stream().map(Evidence::ruleCode).toList();
    }
}
