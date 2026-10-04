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
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务场景：规则持续发布新版本的同时，评分与告警查询并发执行。
 * 每次查询必须完整落在某一个已发布版本上——结论可按该版本规则原样重算出来，
 * 不允许出现"阈值用新版、权重用旧版"这类新旧混用的中间态。
 */
class ConcurrentPublishQueryTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentPublishQueryTest.class);
    private static final String WHALE = "0x" + "aa".repeat(20);
    private static final int PUBLISH_COUNT = 50;

    @Test
    void queriesAlwaysObserveOneCompletePublishedVersion() throws InterruptedException {
        EventStore store = new EventStore();
        RuleSetProvider provider = new RuleSetProvider();
        RiskScoringService scoring = new RiskScoringService(store, provider);
        AlertService alerts = new AlertService(store, scoring);

        EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 1; i <= 6; i++) {
            ingestion.ingest(new TransactionEvent(
                    "evt-" + i, "0xtx" + i, WHALE,
                    "0x" + String.format("%040x", i),
                    new BigDecimal("50000"), "ETH", base.plusSeconds(i * 60L)));
        }
        List<TransactionEvent> snapshot = store.snapshot();

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean publishingDone = new AtomicBoolean(false);
        ConcurrentLinkedQueue<RiskScoreResult> scoreObservations = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<List<RiskScoreResult>> alertObservations = new ConcurrentLinkedQueue<>();

        // 发布线程：连续追加 v2..v51，权重逐版变化，新旧版本结论差异明显；
        // 每版之间稍作停顿，让查询真正落在"发布进行中"的时间窗里
        pool.submit(() -> {
            await(start);
            for (int v = 2; v <= PUBLISH_COUNT + 1; v++) {
                provider.publish(RiskRuleSet.validated(v, new BigDecimal("100000"),
                        5, 5, 10, 40 + v % 10, 10, 50));
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            publishingDone.set(true);
        });
        // 查询线程：发布期间持续评分 + 告警
        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                await(start);
                while (!publishingDone.get()) {
                    scoreObservations.add(scoring.score(WHALE));
                    alertObservations.add(alerts.alertsAboveThreshold());
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        // 校验 1：观察到的每个版本都真实发布过（历史留存中找得到）
        // 校验 2：结论可按该版本规则在同一事件集合上原样重算——证明没有新旧混用
        for (RiskScoreResult r : scoreObservations) {
            RiskRuleSet rulesUsed = provider.version(r.ruleSetVersion());
            assertThat(rulesUsed).as("observed version %s must be a published version",
                    r.ruleSetVersion()).isNotNull();
            RiskScoreResult recomputed = scoring.scoreOnSnapshot(WHALE, snapshot, rulesUsed);
            assertThat(r.score()).isEqualTo(recomputed.score());
            assertThat(r.evidence()).isEqualTo(recomputed.evidence());
            assertThat(r.ruleHits()).isEqualTo(recomputed.ruleHits());
            assertThat(r.eventSetFingerprint()).isEqualTo(recomputed.eventSetFingerprint());
        }
        for (List<RiskScoreResult> alertList : alertObservations) {
            for (RiskScoreResult r : alertList) {
                RiskRuleSet rulesUsed = provider.version(r.ruleSetVersion());
                assertThat(rulesUsed).isNotNull();
                // 告警结论内部自洽：分数达到该版本自己的告警线
                assertThat(r.score()).isGreaterThanOrEqualTo(rulesUsed.alertScoreThreshold());
            }
        }

        long distinctVersions = scoreObservations.stream().map(RiskScoreResult::ruleSetVersion).distinct().count();
        log.info("concurrent run: {} score queries, {} alert queries, observed {} distinct rule versions, "
                        + "all reproducible against retained history",
                scoreObservations.size(), alertObservations.size(), distinctVersions);
        assertThat(scoreObservations).isNotEmpty();
        assertThat(alertObservations).isNotEmpty();
        // 查询确实跨越了多次发布（否则并发校验没有意义）
        assertThat(distinctVersions).isGreaterThan(1);
        assertThat(provider.versions()).hasSize(PUBLISH_COUNT + 1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
