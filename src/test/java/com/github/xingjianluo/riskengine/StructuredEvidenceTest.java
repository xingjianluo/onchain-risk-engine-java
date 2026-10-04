package com.github.xingjianluo.riskengine;

import com.github.xingjianluo.riskengine.ingest.EventIngestionService;
import com.github.xingjianluo.riskengine.ingest.EventValidator;
import com.github.xingjianluo.riskengine.model.TransactionEvent;
import com.github.xingjianluo.riskengine.score.RiskScoreResult;
import com.github.xingjianluo.riskengine.score.RiskScoringService;
import com.github.xingjianluo.riskengine.score.RuleHit;
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
 * 业务场景：自动对账需要结构化判定依据（命中规则、资金方向、对手方、金额、
 * 关联交易）和事件集合指纹。同一批事件无论乱序到达还是重复投递，
 * 结构化依据与指纹必须完全一致；事件集合有增删改，指纹必须变化。
 */
class StructuredEvidenceTest {

    private static final Logger log = LoggerFactory.getLogger(StructuredEvidenceTest.class);
    private static final String HUB = "0x" + "aa".repeat(20);
    private static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");

    /** 6 笔大额转出（触发 LARGE_AMOUNT 与 FAN_OUT）。 */
    private static List<TransactionEvent> buildEvents() {
        List<TransactionEvent> events = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            events.add(new TransactionEvent(
                    "evt-" + i, "0xtx" + i, HUB,
                    "0x" + String.format("%040x", i),
                    new BigDecimal("50000"), "ETH", BASE.plusSeconds(i * 60L)));
        }
        return events;
    }

    private static RiskScoreResult ingestAndScore(List<TransactionEvent> stream) {
        EventStore store = new EventStore();
        EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);
        stream.forEach(ingestion::ingest);
        return new RiskScoringService(store, new RuleSetProvider()).score(HUB);
    }

    @Test
    void ruleHitsExposeRuleDirectionCounterpartiesAmountAndRelatedTransactions() {
        RiskScoreResult result = ingestAndScore(buildEvents());
        log.info("ruleHits={}", result.ruleHits());
        log.info("fingerprint={}", result.eventSetFingerprint());

        assertThat(result.ruleHits()).hasSize(2);

        RuleHit large = result.ruleHits().stream()
                .filter(h -> h.rule().equals("LARGE_AMOUNT")).findFirst().orElseThrow();
        assertThat(large.direction()).isEqualTo(RuleHit.DIRECTION_OUTGOING);
        assertThat(large.counterparties()).hasSize(6);
        assertThat(large.totalAmount()).isEqualByComparingTo(new BigDecimal("300000"));
        assertThat(large.eventIds()).containsExactly("evt-1", "evt-2", "evt-3", "evt-4", "evt-5", "evt-6");
        assertThat(large.txHashes()).containsExactly("0xtx1", "0xtx2", "0xtx3", "0xtx4", "0xtx5", "0xtx6");

        RuleHit fanOut = result.ruleHits().stream()
                .filter(h -> h.rule().equals("FAN_OUT")).findFirst().orElseThrow();
        assertThat(fanOut.direction()).isEqualTo(RuleHit.DIRECTION_OUTGOING);
        assertThat(fanOut.counterparties()).hasSize(6);
        assertThat(fanOut.eventIds()).hasSize(6);

        // 指纹存在且为 SHA-256 十六进制
        assertThat(result.eventSetFingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void fingerprintAndStructuredEvidenceAreStableUnderShuffleAndRedelivery() {
        List<TransactionEvent> events = buildEvents();
        RiskScoreResult baseline = ingestAndScore(events);
        log.info("baseline fingerprint={} ruleHits={}", baseline.eventSetFingerprint(), baseline.ruleHits());

        for (int seed = 1; seed <= 5; seed++) {
            // 乱序 + 每条重复投递 3 份（同 eventId 与换 eventId 两种重复都混入）
            List<TransactionEvent> stream = new ArrayList<>();
            for (int copy = 0; copy < 3; copy++) {
                List<TransactionEvent> shuffled = new ArrayList<>(events);
                Collections.shuffle(shuffled, new Random(seed * 100 + copy));
                int copyIndex = copy;
                shuffled = shuffled.stream()
                        .map(e -> copyIndex == 0 ? e : new TransactionEvent(
                                e.eventId() + "-redelivery-" + copyIndex, e.txHash(),
                                e.fromAddress(), e.toAddress(), e.amount(), e.asset(), e.timestamp()))
                        .toList();
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

        // 增：多一笔事件
        List<TransactionEvent> added = new ArrayList<>(events);
        added.add(new TransactionEvent("evt-7", "0xtx7", HUB,
                "0x" + String.format("%040x", 7), new BigDecimal("100"),
                "ETH", BASE.plusSeconds(420)));
        String afterAdd = ingestAndScore(added).eventSetFingerprint();

        // 删：少一笔事件
        String afterRemove = ingestAndScore(events.subList(0, 5)).eventSetFingerprint();

        // 改：金额不同（换 eventId 与 txHash 模拟修正后的重发）
        List<TransactionEvent> modified = new ArrayList<>(events);
        modified.set(0, new TransactionEvent("evt-1", "0xtx1", HUB,
                "0x" + String.format("%040x", 1), new BigDecimal("50001"),
                "ETH", BASE.plusSeconds(60)));
        String afterModify = ingestAndScore(modified).eventSetFingerprint();

        log.info("baseline={} afterAdd={} afterRemove={} afterModify={}",
                baseline, afterAdd, afterRemove, afterModify);
        assertThat(afterAdd).isNotEqualTo(baseline);
        assertThat(afterRemove).isNotEqualTo(baseline);
        assertThat(afterModify).isNotEqualTo(baseline);
        assertThat(afterAdd).isNotEqualTo(afterRemove);
    }
}
