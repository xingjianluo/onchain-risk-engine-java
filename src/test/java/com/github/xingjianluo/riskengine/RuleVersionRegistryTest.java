package com.github.xingjianluo.riskengine;

import com.github.xingjianluo.riskengine.ingest.EventIngestionService;
import com.github.xingjianluo.riskengine.ingest.EventValidator;
import com.github.xingjianluo.riskengine.model.TransactionEvent;
import com.github.xingjianluo.riskengine.score.RiskRuleSet;
import com.github.xingjianluo.riskengine.score.RiskScoreResult;
import com.github.xingjianluo.riskengine.score.RiskScoringService;
import com.github.xingjianluo.riskengine.score.RuleSetProvider;
import com.github.xingjianluo.riskengine.score.RuleVersionConflictException;
import com.github.xingjianluo.riskengine.store.EventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则版本注册表：每个发布过的版本都留存、同版本号重复发布被拒、
 * 只凭版本号即可复算当时结论（即使中间积累了几百个版本）、
 * 发布与评分并发时每次评分完整落在某一个版本上。
 */
class RuleVersionRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(RuleVersionRegistryTest.class);
    private static final String WHALE = "0x" + "aa".repeat(20);

    private EventStore store;
    private RuleSetProvider provider;
    private RiskScoringService scoring;

    @BeforeEach
    void setUp() {
        store = new EventStore();
        provider = new RuleSetProvider();
        scoring = new RiskScoringService(store, provider);

        EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        // WHALE 向 6 个对手方各转 50000
        for (int i = 1; i <= 6; i++) {
            ingestion.ingest(new TransactionEvent(
                    "evt-" + i, "0xtx" + i, WHALE,
                    "0x" + String.format("%040x", i),
                    new BigDecimal("50000"), "ETH", base.plusSeconds(i * 60L)));
        }
    }

    private static RiskRuleSet rules(int version, String largeThreshold, int largeWeight, int fanOutWeight) {
        return RiskRuleSet.validated(version, new BigDecimal(largeThreshold),
                5, 5, largeWeight, fanOutWeight, 10, 50);
    }

    @Test
    void everyPublishedVersionIsRetainedAndReplayable() {
        // v1 默认规则下：6 笔大额*20 + fanOut 30 = 150 -> 封顶 100
        RiskScoreResult v1Result = scoring.score(WHALE);
        assertThat(v1Result.ruleSetVersion()).isEqualTo(1);
        assertThat(v1Result.score()).isEqualTo(100);

        // 连续发布多个版本，每轮结论都不同
        provider.update(rules(2, "100000", 10, 40)); // 大额不再命中 -> 只剩 fanOut 40
        provider.update(rules(3, "100000", 10, 35)); // fanOut 权重改 35
        log.info("published versions: {}", provider.versions());

        assertThat(provider.versions()).containsExactly(1, 2, 3);
        assertThat(scoring.score(WHALE).score()).isEqualTo(35);

        // 只凭版本号复算历史结论：与当时的结论逐项一致
        RiskScoreResult replayV1 = scoring.score(WHALE, 1);
        RiskScoreResult replayV2 = scoring.score(WHALE, 2);
        log.info("replay v1: score={} fingerprint={}", replayV1.score(), replayV1.eventSetFingerprint());
        log.info("replay v2: score={} evidence={}", replayV2.score(), replayV2.evidence());

        assertThat(replayV1.score()).isEqualTo(v1Result.score());
        assertThat(replayV1.evidence()).isEqualTo(v1Result.evidence());
        assertThat(replayV1.ruleHits()).isEqualTo(v1Result.ruleHits());
        assertThat(replayV1.eventSetFingerprint()).isEqualTo(v1Result.eventSetFingerprint());
        assertThat(replayV2.score()).isEqualTo(40);
        assertThat(replayV2.ruleSetVersion()).isEqualTo(2);

        // 历史版本内容就是当时发布的那一份
        assertThat(provider.version(1)).isEqualTo(RiskRuleSet.defaultRules());
        assertThat(provider.version(2).fanOutWeight()).isEqualTo(40);
    }

    @Test
    void republishingSameVersionNumberIsRejectedAndHistoryUntouched() {
        provider.update(rules(2, "100000", 10, 40));
        RiskRuleSet publishedV2 = provider.version(2);

        // 同版本号、不同内容 -> 拒绝
        assertThatThrownBy(() -> provider.update(rules(2, "1", 99, 99)))
                .isInstanceOf(RuleVersionConflictException.class)
                .hasMessageContaining("already published");
        // 同版本号、同内容 -> 同样拒绝（不允许任何形式的覆盖）
        assertThatThrownBy(() -> provider.update(rules(2, "100000", 10, 40)))
                .isInstanceOf(RuleVersionConflictException.class);
        // 版本回退 -> 拒绝
        assertThatThrownBy(() -> provider.update(rules(1, "1", 1, 1)))
                .isInstanceOf(RuleVersionConflictException.class);

        // 历史内容未被污染，当前版本仍是 v2
        assertThat(provider.version(2)).isSameAs(publishedV2);
        assertThat(provider.current().version()).isEqualTo(2);
        assertThat(provider.versions()).containsExactly(1, 2);
        log.info("after rejected republishes, versions still {}", provider.versions());
    }

    @Test
    void unknownVersionLookupIsRejected() {
        assertThatThrownBy(() -> scoring.score(WHALE, 999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown rule version");
    }

    @Test
    void hundredsOfVersionsAccumulateWithoutSlowingCurrentQueries() {
        int versionCount = 300;
        for (int v = 2; v <= versionCount; v++) {
            provider.update(rules(v, "100000", 10, 40));
        }
        assertThat(provider.versions()).hasSize(versionCount);

        // 任意抽查的历史版本都能复算，且结论自洽（v2..v300 规则相同 -> 40 分）
        for (int v : new int[]{2, 57, 150, 299, 300}) {
            RiskScoreResult replayed = scoring.score(WHALE, v);
            assertThat(replayed.ruleSetVersion()).isEqualTo(v);
            assertThat(replayed.score()).isEqualTo(40);
        }
        // v1 依然可复算当时的 100 分
        assertThat(scoring.score(WHALE, 1).score()).isEqualTo(100);

        // 当前评分走 volatile 引用，不触碰版本表；300 个版本下耗时应在毫秒级
        long startNanos = System.nanoTime();
        int rounds = 200;
        for (int i = 0; i < rounds; i++) {
            scoring.score(WHALE);
        }
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("current-rule scoring with {} published versions: {} rounds in {} ms",
                versionCount, rounds, elapsedMillis);
        assertThat(elapsedMillis).isLessThan(5000);
    }

    @Test
    void concurrentPublishAndScoringAlwaysLandOnOneCompleteVersion() throws InterruptedException {
        int publishRounds = 50;
        int scoreRounds = 500;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        Set<Integer> observedVersions = ConcurrentHashMap.newKeySet();
        AtomicInteger inconsistencies = new AtomicInteger();

        // 发布线程：v2..v51 依次追加
        pool.submit(() -> {
            await(start);
            for (int v = 2; v <= publishRounds + 1; v++) {
                provider.update(rules(v, "100000", 10, 40));
            }
        });
        // 评分线程：每次评分结果的版本号必须是某个已完整发布的版本，
        // 且 result 内部版本号与规则内容自洽（v1 -> 100 分，v2+ -> 40 分）
        for (int i = 0; i < scoreRounds; i++) {
            pool.submit(() -> {
                await(start);
                RiskScoreResult r = scoring.score(WHALE);
                observedVersions.add(r.ruleSetVersion());
                boolean consistent = r.ruleSetVersion() == 1
                        ? r.score() == 100
                        : r.score() == 40;
                if (!consistent) {
                    inconsistencies.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        log.info("concurrent publish/score: observed versions count={} inconsistencies={}",
                observedVersions.size(), inconsistencies.get());
        assertThat(inconsistencies.get()).isZero();
        assertThat(observedVersions).isNotEmpty();
        // 发布全部完成后，所有版本都已留存
        assertThat(provider.versions()).hasSize(publishRounds + 1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
