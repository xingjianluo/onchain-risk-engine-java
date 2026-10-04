package com.github.xingjianluo.riskengine;

import com.github.xingjianluo.riskengine.ingest.EventIngestionService;
import com.github.xingjianluo.riskengine.ingest.EventValidator;
import com.github.xingjianluo.riskengine.model.TransactionEvent;
import com.github.xingjianluo.riskengine.score.RuleHit;
import com.github.xingjianluo.riskengine.score.RiskScoreResult;
import com.github.xingjianluo.riskengine.score.RiskScoringService;
import com.github.xingjianluo.riskengine.score.RuleSetProvider;
import com.github.xingjianluo.riskengine.store.EventStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化判定依据（ruleHits）与事件集合指纹（eventSetFingerprint）：
 * 同一批事件无论乱序到达还是重复投递，指纹与结构化依据完全一致；
 * 事件集合有增删改，指纹必须变化；原有文字依据链继续保留。
 */
class StructuredEvidenceTest {

    private static final Logger log = LoggerFactory.getLogger(StructuredEvidenceTest.class);
    private static final String WHALE = "0x" + "aa".repeat(20);
    private static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");

    /** 6 笔大额转出，命中 LARGE_AMOUNT x6 + FAN_OUT。 */
    private static List<TransactionEvent> buildEvents() {
        List<TransactionEvent> events = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            events.add(new TransactionEvent(
                    "evt-" + i, "0xtx" + i, WHALE,
                    "0x" + String.format("%040x", i),
                    new BigDecimal("50000"), "ETH", BASE.plusSeconds(i * 60L)));
        }
        return events;
    }

    private static RiskScoreResult ingestAndScore(List<TransactionEvent> stream) {
        EventStore store = new EventStore();
        EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);
        stream.forEach(ingestion::ingest);
        return new RiskScoringService(store, new RuleSetProvider()).score(WHALE);
    }

    @Test
    void fingerprintAndRuleHitsAreIdenticalUnderShuffleAndDuplicateDelivery() {
        List<TransactionEvent> events = buildEvents();
        RiskScoreResult baseline = ingestAndScore(events);
        log.info("baseline: score={} fingerprint={} ruleHits={}",
                baseline.score(), baseline.eventSetFingerprint(), baseline.ruleHits());

        for (int seed = 1; seed <= 5; seed++) {
            // 乱序 + 每条重复投递 3 次
            List<TransactionEvent> stream = new ArrayList<>();
            for (int copy = 0; copy < 3; copy++) {
                List<TransactionEvent> shuffled = new ArrayList<>(events);
                Collections.shuffle(shuffled, new Random(seed * 100 + copy));
                stream.addAll(shuffled);
            }
            RiskScoreResult rescored = ingestAndScore(stream);
            log.info("seed={} fingerprint={} equal={}", seed, rescored.eventSetFingerprint(),
                    rescored.eventSetFingerprint().equals(baseline.eventSetFingerprint()));

            assertThat(rescored.eventSetFingerprint()).isEqualTo(baseline.eventSetFingerprint());
            assertThat(rescored.ruleHits()).isEqualTo(baseline.ruleHits());
            assertThat(rescored.evidence()).isEqualTo(baseline.evidence());
            assertThat(rescored.score()).isEqualTo(baseline.score());
        }
    }

    @Test
    void fingerprintChangesWhenEventSetChanges() {
        List<TransactionEvent> events = buildEvents();
        String baseline = ingestAndScore(events).eventSetFingerprint();

        // 增：多一笔交易
        List<TransactionEvent> added = new ArrayList<>(events);
        added.add(new TransactionEvent("evt-7", "0xtx7", WHALE,
                "0x" + String.format("%040x", 7), new BigDecimal("50000"), "ETH",
                BASE.plusSeconds(420)));
        String afterAdd = ingestAndScore(added).eventSetFingerprint();

        // 删：少一笔交易
        String afterRemove = ingestAndScore(events.subList(0, 5)).eventSetFingerprint();

        // 改：金额不同（换投递编号与 txHash，模拟同一集合中一条记录被修改）
        List<TransactionEvent> modified = new ArrayList<>(events);
        TransactionEvent e1 = modified.get(0);
        modified.set(0, new TransactionEvent(e1.eventId(), e1.txHash(), e1.fromAddress(),
                e1.toAddress(), new BigDecimal("60000"), e1.asset(), e1.timestamp()));
        String afterModify = ingestAndScore(modified).eventSetFingerprint();

        log.info("baseline={}", baseline);
        log.info("afterAdd={} afterRemove={} afterModify={}", afterAdd, afterRemove, afterModify);
        assertThat(afterAdd).isNotEqualTo(baseline);
        assertThat(afterRemove).isNotEqualTo(baseline);
        assertThat(afterModify).isNotEqualTo(baseline);
    }

    @Test
    void ruleHitsExposeRuleDirectionCounterpartyAmountAndLinkedTransactions() {
        RiskScoreResult result = ingestAndScore(buildEvents());
        log.info("ruleHits:");
        result.ruleHits().forEach(h -> log.info("  {}", h));

        // 6 条 LARGE_AMOUNT 单笔命中 + 1 条 FAN_OUT 聚合命中
        List<RuleHit> largeAmountHits = result.ruleHits().stream()
                .filter(h -> h.rule().equals("LARGE_AMOUNT")).toList();
        List<RuleHit> fanOutHits = result.ruleHits().stream()
                .filter(h -> h.rule().equals("FAN_OUT")).toList();
        assertThat(largeAmountHits).hasSize(6);
        assertThat(fanOutHits).hasSize(1);

        // 单笔命中：方向、对手方、金额、关联交易记录齐全
        RuleHit first = largeAmountHits.get(0);
        assertThat(first.direction()).isEqualTo("OUTGOING");
        assertThat(first.scoreContribution()).isEqualTo(20);
        assertThat(first.amount()).isEqualByComparingTo("50000");
        assertThat(first.asset()).isEqualTo("ETH");
        assertThat(first.counterparties())
                .containsExactly("0x" + String.format("%040x", 1));
        assertThat(first.eventIds()).containsExactly("evt-1");
        assertThat(first.txHashes()).containsExactly("0xtx1");

        // 聚合命中：6 个对手方、6 条关联交易、合计金额
        RuleHit fanOut = fanOutHits.get(0);
        assertThat(fanOut.direction()).isEqualTo("OUTGOING");
        assertThat(fanOut.counterparties()).hasSize(6);
        assertThat(fanOut.eventIds()).containsExactly(
                "evt-1", "evt-2", "evt-3", "evt-4", "evt-5", "evt-6");
        assertThat(fanOut.txHashes()).containsExactly(
                "0xtx1", "0xtx2", "0xtx3", "0xtx4", "0xtx5", "0xtx6");
        assertThat(fanOut.amount()).isEqualByComparingTo("300000");
    }

    @Test
    void legacyFieldsAndHumanReadableEvidenceArePreserved() {
        RiskScoreResult result = ingestAndScore(buildEvents());
        log.info("legacy evidence={}", result.evidence());

        // 既有字段与文字依据链格式不变
        assertThat(result.address()).isEqualTo(WHALE);
        assertThat(result.score()).isEqualTo(100);
        assertThat(result.ruleSetVersion()).isEqualTo(1);
        assertThat(result.evidence().get(0)).isEqualTo("ruleset=v1");
        assertThat(result.evidence()).anyMatch(e -> e.startsWith("LARGE_AMOUNT(+20): event=evt-1 tx=0xtx1"));
        assertThat(result.evidence()).anyMatch(e -> e.startsWith("FAN_OUT(+30)"));
        assertThat(result.evidence()).anyMatch(e -> e.startsWith("TOTAL=100"));
        // 新增字段非空
        assertThat(result.ruleHits()).isNotEmpty();
        assertThat(result.eventSetFingerprint()).hasSize(64); // SHA-256 hex
    }
}
