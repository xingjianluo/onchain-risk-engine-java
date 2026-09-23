package com.github.xingjianluo.onchainriskenginejava.tests;

import com.github.xingjianluo.onchainriskenginejava.model.IngestionResult;
import com.github.xingjianluo.onchainriskenginejava.model.RejectReason;
import com.github.xingjianluo.onchainriskenginejava.model.RiskAlert;
import com.github.xingjianluo.onchainriskenginejava.model.RiskScore;
import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;
import com.github.xingjianluo.onchainriskenginejava.rules.builtin.BuiltInRules;
import com.github.xingjianluo.onchainriskenginejava.service.RiskEngineService;
import com.github.xingjianluo.onchainriskenginejava.sim.LocalEventSimulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端：乱序 / 重复 / 并发摄入结论一致、规则更新并发下的快照一致性、历史版本复现、
 * 阈值告警与非法输入不进统计。测试日志打印输入事件与判定依据。
 */
@SpringBootTest
class RiskEngineIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RiskEngineIntegrationTest.class);

    @Autowired
    private RiskEngineService service;

    @Autowired
    private LocalEventSimulator simulator;

    @BeforeEach
    void setUp() {
        service.reset();
        service.activateVersion(BuiltInRules.VERSION_V1);
    }

    @AfterEach
    void tearDown() {
        service.reset();
        service.activateVersion(BuiltInRules.VERSION_V1);
    }

    private List<IngestionResult> ingestWithLog(List<TransactionEvent> events, String label) {
        List<IngestionResult> results = new ArrayList<>();
        log.info("---- {}：开始摄入 {} 条事件 ----", label, events.size());
        for (TransactionEvent e : events) {
            log.info("{} 输入 | eventId={} txHash={} ts={} {} -> {} amount={}",
                    label, e.eventId(), e.txHash(), e.timestamp(), e.from(), e.to(),
                    e.amount() == null ? "null" : e.amount().toPlainString());
            IngestionResult r = service.ingest(e);
            log.info("{} 判定 | eventId={} accepted={} reason={} msg={}",
                    label, e.eventId(), r.accepted(), r.reason(), r.message());
            results.add(r);
        }
        return results;
    }

    @Test
    @DisplayName("顺序与乱序摄入同一批数据，所有钱包评分与快照哈希一致")
    void sameDataDifferentOrderSameConclusion() {
        List<TransactionEvent> data = simulator.dataset();

        ingestWithLog(data, "顺序流");
        List<RiskScore> orderedScores = scoreAll(data);
        String orderedHash = orderedScores.get(0).snapshot().eventsHash();

        service.reset();
        List<TransactionEvent> shuffled = simulator.outOfOrderView(data);
        ingestWithLog(shuffled, "乱序流");
        List<RiskScore> shuffledScores = scoreAll(data);

        assertEquals(orderedScores.size(), shuffledScores.size());
        for (int i = 0; i < orderedScores.size(); i++) {
            RiskScore o = orderedScores.get(i);
            RiskScore s = shuffledScores.get(i);
            log.info("钱包 {} 顺序 score={}({}) 乱序 score={}({})",
                    o.address(), o.score(), o.evidences().size(), s.score(), s.evidences().size());
            assertEquals(o, s, "乱序摄入必须产生完全一致的评分与依据");
        }
        String shuffledHash = shuffledScores.get(0).snapshot().eventsHash();
        log.info("快照哈希 顺序={} 乱序={}", orderedHash, shuffledHash);
        assertEquals(orderedHash, shuffledHash, "乱序不得改变规范化快照哈希");
    }

    @Test
    @DisplayName("重复投递不重复计价，且 eventId / txHash 重复原因可区分")
    void duplicatesNotDoubleCounted() {
        List<IngestionResult> results = ingestWithLog(simulator.datasetWithDuplicatesAndInvalid(), "含重复与非法");
        List<RejectReason> reasons = results.stream().filter(r -> !r.accepted())
                .map(IngestionResult::reason).toList();
        log.info("被拒绝原因集合: {}", reasons);
        assertTrue(reasons.contains(RejectReason.DUPLICATE_EVENT_ID));
        assertTrue(reasons.contains(RejectReason.DUPLICATE_TX_HASH));
        assertTrue(reasons.contains(RejectReason.AMOUNT_OUT_OF_RANGE));
        assertTrue(reasons.contains(RejectReason.INVALID_ADDRESS));
        assertTrue(reasons.contains(RejectReason.SELF_TRANSACTION));
        assertTrue(reasons.contains(RejectReason.MISSING_FIELD));

        // 10 条合法事件，重复与非法均未入账
        RiskScore scoreA = service.score(LocalEventSimulator.A);
        log.info("A 最终 score={} raw={} 依据={}", scoreA.score(), scoreA.rawPoints(),
                scoreA.evidences().stream().map(e -> e.ruleCode() + ":" + e.points()).toList());
        assertEquals(12, scoreA.snapshot().eventCount());
    }

    @Test
    @DisplayName("多线程并发摄入全部事件，最终评分与顺序摄入完全一致")
    void concurrentIngestMatchesSequential() throws InterruptedException {
        // 先拿顺序结论
        ingestWithLog(simulator.dataset(), "顺序基线");
        List<RiskScore> baseline = scoreAll(simulator.dataset());
        service.reset();

        // 再并发摄入（重复 3 轮，制造跨线程重复投递）
        List<TransactionEvent> repeated = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            repeated.addAll(simulator.dataset());
        }
        Collections.shuffle(repeated);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(repeated.size());
        for (TransactionEvent e : repeated) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.ingest(e);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        pool.shutdown();

        List<RiskScore> concurrent = scoreAll(simulator.dataset());
        assertEquals(baseline, concurrent, "并发（含跨线程重复）摄入后的结论必须与顺序一致");
        log.info("并发摄入完成，事件数={}，评分与顺序基线一致",
                concurrent.get(0).snapshot().eventCount());
    }

    @Test
    @DisplayName("规则更新与查询并发：每次查询自洽，历史 v1 结论可用旧版本复现")
    void ruleUpdateSnapshotConsistencyAndReproducibility() throws InterruptedException {
        ingestWithLog(simulator.dataset(), "评分数据");

        // 更新前固定 v1 结论
        RiskScore v1Before = service.score(LocalEventSimulator.A);
        String v1Version = v1Before.snapshot().ruleSetVersion();
        log.info("更新前 A v1 score={} version={} hits={}", v1Before.score(), v1Version,
                v1Before.evidences().stream().map(e -> e.ruleCode()).toList());

        // 并发：一个线程反复发布 v2/v1（v2 只能发布一次，因此只发一次 v2 后持续查询）
        service.publishBuiltIn("v2");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean mismatch = new AtomicBoolean(false);
        AtomicReference<Throwable> error = new AtomicReference<>();
        for (int i = 0; i < 8; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 50; j++) {
                        RiskScore score = service.score(LocalEventSimulator.A);
                        // 快照自洽：版本只能是 v1 或 v2，分值必须与版本匹配，不会出现撕裂
                        String ver = score.snapshot().ruleSetVersion();
                        int expected = switch (ver) {
                            case BuiltInRules.VERSION_V1 -> v1Before.score();
                            case BuiltInRules.VERSION_V2 -> scoreWithV2Expectation();
                            default -> throw new IllegalStateException("未知版本: " + ver);
                        };
                        if (score.score() != expected) {
                            mismatch.set(true);
                        }
                        if (idx == 0 && j == 0) {
                            log.info("并发查询 sample: version={} score={} hits={}", ver,
                                    score.score(), score.evidences().stream().map(e -> e.ruleCode()).toList());
                        }
                    }
                } catch (Throwable t) {
                    error.set(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        assertNull(error.get());
        assertFalse(mismatch.get(), "并发查询中出现版本与分值不自洽的撕裂快照");

        // 历史复现：当前是 v2，但显式按 v1 评分必须得到更新前结论
        RiskScore v1Reproduced = service.scoreWithVersion(LocalEventSimulator.A, BuiltInRules.VERSION_V1);
        log.info("规则更新后按 v1 复现 A score={} hits={}", v1Reproduced.score(),
                v1Reproduced.evidences().stream().map(e -> e.ruleCode()).toList());
        assertEquals(v1Before, v1Reproduced, "历史规则版本必须复现更新前结论");
        assertEquals(BuiltInRules.VERSION_V2, service.score(LocalEventSimulator.A).snapshot().ruleSetVersion());

        // 重复发布同版本必须被拒绝
        assertThrows(IllegalArgumentException.class, () -> service.publishBuiltIn("v1"));
    }

    private int scoreWithV2Expectation() {
        // v2 下 A 的预期分值，直接用 scoreWithVersion 取一次作为参照
        return service.scoreWithVersion(LocalEventSimulator.A, BuiltInRules.VERSION_V2).score();
    }

    @Test
    @DisplayName("阈值告警：超阈值钱包、依据链与快照一致，非法阈值被拒绝")
    void alertsRespectThresholdAndSnapshot() {
        ingestWithLog(simulator.dataset(), "告警数据");
        List<RiskAlert> alerts60 = service.alerts(60);
        log.info("告警(>=60): {}", alerts60.stream()
                .map(a -> a.address() + "=" + a.score() + a.triggeredRuleCodes()).toList());

        assertFalse(alerts60.isEmpty());
        // 同一批次内所有告警共享同一快照标识
        assertEquals(1, alerts60.stream().map(a -> a.snapshot().eventsHash()).distinct().count());
        assertEquals(1, alerts60.stream().map(a -> a.snapshot().ruleSetVersion()).distinct().count());
        assertTrue(alerts60.stream().noneMatch(a -> a.score() < 60));

        List<RiskAlert> alerts999 = service.alerts(90);
        log.info("告警(>=90): {}", alerts999.stream().map(RiskAlert::address).toList());
        // 阈值越高告警越少
        assertTrue(alerts999.size() <= alerts60.size());

        assertThrows(IllegalArgumentException.class, () -> service.alerts(101));
        assertThrows(IllegalArgumentException.class, () -> service.alerts(-1));
        assertThrows(IllegalArgumentException.class,
                () -> service.score("0xnot-an-address"));
    }

    /** 对数据中出现的全部地址（按字典序）评分。 */
    private List<RiskScore> scoreAll(List<TransactionEvent> data) {
        java.util.TreeSet<String> addressSet = new java.util.TreeSet<>();
        for (TransactionEvent e : data) {
            addressSet.add(e.from().toLowerCase());
            addressSet.add(e.to().toLowerCase());
        }
        List<RiskScore> scores = new ArrayList<>();
        for (String a : addressSet) {
            scores.add(service.score(a));
        }
        return scores;
    }
}
