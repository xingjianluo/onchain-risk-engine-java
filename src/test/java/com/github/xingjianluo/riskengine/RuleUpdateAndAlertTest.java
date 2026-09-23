package com.github.xingjianluo.riskengine;

import com.github.xingjianluo.riskengine.alert.AlertService;
import com.github.xingjianluo.riskengine.ingest.EventIngestionService;
import com.github.xingjianluo.riskengine.ingest.EventValidator;
import com.github.xingjianluo.riskengine.model.TransactionEvent;
import com.github.xingjianluo.riskengine.score.RiskRuleSet;
import com.github.xingjianluo.riskengine.score.RiskScoreResult;
import com.github.xingjianluo.riskengine.score.RiskScoringService;
import com.github.xingjianluo.riskengine.score.RuleSetProvider;
import com.github.xingjianluo.riskengine.store.EventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则更新场景：历史结论可按旧版本复现；规则更新与告警查询并发时
 * 每次查询都落在某一个完整规则版本上（快照语义）。
 */
class RuleUpdateAndAlertTest {

    private static final Logger log = LoggerFactory.getLogger(RuleUpdateAndAlertTest.class);
    private static final String WHALE = "0x" + "aa".repeat(20);

    private EventStore store;
    private RuleSetProvider ruleSetProvider;
    private RiskScoringService scoringService;
    private AlertService alertService;

    @BeforeEach
    void setUp() {
        store = new EventStore();
        ruleSetProvider = new RuleSetProvider();
        scoringService = new RiskScoringService(store, ruleSetProvider);
        alertService = new AlertService(store, ruleSetProvider, scoringService);

        EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        // WHALE 向 6 个对手方各转 50000（默认规则：大额阈值 10000，fanOut 阈值 5）
        for (int i = 1; i <= 6; i++) {
            TransactionEvent event = new TransactionEvent(
                    "evt-" + i, "0xtx" + i, WHALE,
                    "0x" + String.format("%040x", i),
                    new BigDecimal("50000"), "ETH", base.plusSeconds(i * 60L));
            var result = ingestion.ingest(event);
            log.info("input={} -> accepted={}", event, result.accepted());
        }
    }

    @Test
    void historicalConclusionReproducibleAfterRuleUpdate() {
        RiskScoreResult before = scoringService.score(WHALE);
        log.info("before update: score={} version={} evidence={}",
                before.score(), before.ruleSetVersion(), before.evidence());
        // 默认规则: 6 笔大额 * 20 + fanOut 30 = 150 -> 封顶 100
        assertThat(before.score()).isEqualTo(100);
        assertThat(before.ruleSetVersion()).isEqualTo(1);

        // 更新规则：大额阈值提到 100000，权重变化
        RiskRuleSet v2 = RiskRuleSet.validated(2, new BigDecimal("100000"),
                5, 5, 10, 40, 10, 50);
        ruleSetProvider.update(v2);

        RiskScoreResult after = scoringService.score(WHALE);
        log.info("after update: score={} version={} evidence={}",
                after.score(), after.ruleSetVersion(), after.evidence());
        // 新规则下大额不再命中，只剩 fanOut 40
        assertThat(after.score()).isEqualTo(40);
        assertThat(after.ruleSetVersion()).isEqualTo(2);

        // 用保存的旧规则集引用复现历史结论：与更新前完全一致
        RiskScoreResult reproduced = scoringService.scoreOnSnapshot(
                WHALE, store.snapshot(), RiskRuleSet.defaultRules());
        log.info("reproduced with v1 rules: score={} evidence={}",
                reproduced.score(), reproduced.evidence());
        assertThat(reproduced.score()).isEqualTo(before.score());
        assertThat(reproduced.evidence()).isEqualTo(before.evidence());
    }

    @Test
    void alertQueryUsesConsistentRuleSnapshotUnderConcurrentUpdates() throws InterruptedException {
        RiskRuleSet v2 = RiskRuleSet.validated(2, new BigDecimal("100000"),
                5, 5, 10, 40, 10, 50);
        RiskRuleSet v3 = RiskRuleSet.validated(3, new BigDecimal("1"),
                100, 100, 5, 5, 5, 95);

        int queries = 200;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<List<RiskScoreResult>> observations = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < queries; i++) {
            pool.submit(() -> {
                await(start);
                observations.add(alertService.alertsAboveThreshold());
            });
        }
        pool.submit(() -> {
            await(start);
            ruleSetProvider.update(v2);
            ruleSetProvider.update(v3);
        });
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Set<Integer> observedVersions = new java.util.HashSet<>();
        for (List<RiskScoreResult> alerts : observations) {
            assertThat(alerts).isNotEmpty();
            for (RiskScoreResult r : alerts) {
                observedVersions.add(r.ruleSetVersion());
                // 快照语义：每条结论的版本必须属于某一个已发布的完整规则集
                assertThat(r.ruleSetVersion()).isIn(1, 2, 3);
                // 结论内部自洽：得分必须达到该版本规则集自身的告警线
                int threshold = switch (r.ruleSetVersion()) {
                    case 1 -> RiskRuleSet.DEFAULT_ALERT_THRESHOLD;
                    case 2 -> 50;
                    default -> 95;
                };
                assertThat(r.score()).isGreaterThanOrEqualTo(threshold);
            }
        }
        log.info("observed rule versions across {} concurrent queries: {}", queries, observedVersions);
        assertThat(observedVersions).isNotEmpty();
    }

    @Test
    void rejectsInvalidRuleSet() {
        assertThatThrownBy(() -> RiskRuleSet.validated(0, new BigDecimal("1"), 1, 1, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskRuleSet.validated(2, BigDecimal.ZERO, 1, 1, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskRuleSet.validated(2, new BigDecimal("1"), 0, 1, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskRuleSet.validated(2, new BigDecimal("1"), 1, 1, 1, 1, 1, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void alertResultsAreDeterministicallyOrdered() {
        List<RiskScoreResult> first = alertService.alertsAboveThreshold();
        List<RiskScoreResult> second = alertService.alertsAboveThreshold();
        log.info("alerts run1={}", first);
        log.info("alerts run2={}", second);
        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(RiskScoreResult::address).contains(WHALE);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
