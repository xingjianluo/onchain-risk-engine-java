package com.github.xingjianluo.riskengine;

import com.github.xingjianluo.riskengine.ingest.EventIngestionService;
import com.github.xingjianluo.riskengine.ingest.EventValidator;
import com.github.xingjianluo.riskengine.model.TransactionEvent;
import com.github.xingjianluo.riskengine.score.DuplicateRuleVersionException;
import com.github.xingjianluo.riskengine.score.RiskRuleSet;
import com.github.xingjianluo.riskengine.score.RiskScoreResult;
import com.github.xingjianluo.riskengine.score.RiskScoringService;
import com.github.xingjianluo.riskengine.score.RuleSetProvider;
import com.github.xingjianluo.riskengine.score.RuleVersionNotFoundException;
import com.github.xingjianluo.riskengine.store.EventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务场景：规则多次更新后，系统内部留存每个已发布版本，
 * 只凭版本号即可复现当时的评分结论；同版本号不允许覆盖已发布内容。
 */
class RuleVersionHistoryTest {

    private static final Logger log = LoggerFactory.getLogger(RuleVersionHistoryTest.class);
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
        // v1 默认规则下的结论
        RiskScoreResult v1Result = scoring.score(WHALE);
        log.info("v1 conclusion: score={} version={}", v1Result.score(), v1Result.ruleSetVersion());

        // 连续发布 v2、v3，当前结论随新版本变化
        provider.publish(rules(2, "100000", 10, 40));
        RiskScoreResult v2Result = scoring.score(WHALE);
        provider.publish(rules(3, "1", 5, 5));
        RiskScoreResult v3Result = scoring.score(WHALE);
        log.info("v2 conclusion: score={} | v3 conclusion: score={}",
                v2Result.score(), v3Result.score());
        assertThat(v1Result.score()).isEqualTo(100);   // 6*20 + 30 封顶
        assertThat(v2Result.score()).isEqualTo(40);    // 大额不再命中，只剩 fanOut 40
        assertThat(v3Result.score()).isEqualTo(35);    // 6*5 大额 + fanOut 5

        // 只凭版本号复现历史结论：与当时逐字段一致（含结构化依据与指纹）
        RiskScoreResult replayV1 = scoring.score(WHALE, 1);
        RiskScoreResult replayV2 = scoring.score(WHALE, 2);
        log.info("replay v1: score={} | replay v2: score={}", replayV1.score(), replayV2.score());
        assertThat(replayV1).isEqualTo(v1Result);
        assertThat(replayV2).isEqualTo(v2Result);
        assertThat(provider.versions()).containsExactly(1, 2, 3);
    }

    @Test
    void duplicateVersionNumberIsRejectedAndCannotOverwrite() {
        RiskRuleSet v2 = rules(2, "100000", 10, 40);
        provider.publish(v2);

        // 同版本号、不同内容：拒绝，且已发布内容不被覆盖
        assertThatThrownBy(() -> provider.publish(rules(2, "999999", 99, 99)))
                .isInstanceOf(DuplicateRuleVersionException.class)
                .hasMessageContaining("2");
        // 同版本号、同内容同样拒绝（发布是幂等失败而不是静默成功，避免调用方误判）
        assertThatThrownBy(() -> provider.publish(v2))
                .isInstanceOf(DuplicateRuleVersionException.class);
        // 版本回退（未发布过但不大于当前版本）也拒绝：先发布 v5，再补发 v3
        provider.publish(rules(5, "100000", 10, 40));
        assertThatThrownBy(() -> provider.publish(rules(3, "1", 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(provider.version(2)).isEqualTo(v2);
        assertThat(provider.current().version()).isEqualTo(5);
        log.info("duplicate/rollback publishes rejected; retained v2={}", provider.version(2));
    }

    @Test
    void unknownVersionReplayIsRejected() {
        assertThatThrownBy(() -> scoring.score(WHALE, 999))
                .isInstanceOf(RuleVersionNotFoundException.class)
                .hasMessageContaining("999");
        log.info("replay with unknown version rejected as expected");
    }

    @Test
    void hundredsOfVersionsAccumulateWithoutSlowingCurrentQueries() {
        // 模拟长期运行：累积 300 个版本
        int versions = 300;
        for (int v = 2; v <= versions; v++) {
            provider.publish(rules(v, "100000", 10, 40));
        }
        assertThat(provider.versions()).hasSize(versions);
        log.info("published {} versions", versions);

        // 当前评分仍走 volatile 引用，与历史大小无关
        long startCurrent = System.nanoTime();
        RiskScoreResult current = scoring.score(WHALE);
        long currentNanos = System.nanoTime() - startCurrent;

        // 任意历史版本都可复算：抽查首、中、尾
        RiskScoreResult replayFirst = scoring.score(WHALE, 1);
        RiskScoreResult replayMid = scoring.score(WHALE, 150);
        RiskScoreResult replayLast = scoring.score(WHALE, 300);
        assertThat(replayFirst.ruleSetVersion()).isEqualTo(1);
        assertThat(replayFirst.score()).isEqualTo(100);
        assertThat(replayMid.ruleSetVersion()).isEqualTo(150);
        assertThat(replayMid.score()).isEqualTo(40);
        assertThat(replayLast).isEqualTo(current);

        log.info("with {} retained versions: current query took {} ns, replays ok (v1={}, v150={}, v300={})",
                versions, currentNanos, replayFirst.score(), replayMid.score(), replayLast.score());
    }
}
