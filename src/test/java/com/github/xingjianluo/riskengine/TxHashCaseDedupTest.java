package com.github.xingjianluo.riskengine;

import com.github.xingjianluo.riskengine.ingest.EventIngestionService;
import com.github.xingjianluo.riskengine.ingest.EventValidator;
import com.github.xingjianluo.riskengine.model.IngestResult;
import com.github.xingjianluo.riskengine.model.RejectionReason;
import com.github.xingjianluo.riskengine.model.TransactionEvent;
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
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务场景：同一笔链上交易（txHash 相同、仅大小写写法不同）被上游反复重投，
 * 有的重投换了投递编号（eventId），有的连投递编号都不换。无论哪种大小写写法
 * 先到、按什么顺序到达、重复投递多少次，业务上只能计价一次；评分结果里显示的
 * 交易哈希、结构化判定依据（ruleHits）、事件集合指纹（eventSetFingerprint）
 * 都必须逐字段一致——否则同一批事件换个到达顺序就会对不上账。
 *
 * <p>覆盖：
 * <ol>
 *   <li>多个确定性到达顺序（大写先到 / 小写先到 / 混写先到）单线程逐条灌入，
 *       且混入"换编号"与"不换编号"两种重投：只接受一条；留存哈希、证据、
 *       结构化依据与指纹全部与基准逐字段一致；DUPLICATE_EVENT 与
 *       DUPLICATE_TRANSACTION 两种拒因都出现且可区分；</li>
 *   <li>32 线程并发灌入同一笔交易的不同写法、不同投递编号：
 *       无预埋时恰好接受一条且哈希被规范为小写；预埋一条后并发重灌时
 *       总接受数仍为 1，评分结果与单线程基准逐字段一致；</li>
 *   <li>对照组：哈希确实不同（不止大小写差异）仍是不同交易，各自计价。</li>
 * </ol>
 *
 * <p>说明：eventId 是投递编号，属于哪一次投递被接受的事实记录；要跨到达顺序
 * 比对"逐字段一致"，各顺序中被接受的须是同一个投递编号、只是哈希写法不同。
 * 换了投递编号的重投一律被拒绝，因此不会影响留存内容。</p>
 */
class TxHashCaseDedupTest {

    private static final Logger log = LoggerFactory.getLogger(TxHashCaseDedupTest.class);

    private static final String HUB = "0x" + "aa".repeat(20);
    private static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z");

    /** 同一笔链上交易的唯一规范哈希（小写），所有结论里只能出现这个写法。 */
    private static final String TX1_CANONICAL = "0xabcdef1";
    /** 同一笔交易的不同大小写写法（含 0X 前缀大写）。 */
    private static final String TX1_UPPER = "0XABCDEF1";
    private static final String TX1_LOWER = "0xabcdef1";
    private static final String TX1_MIXED = "0xAbCdEf1";

    private static TransactionEvent tx1Variant(String eventId, String writtenTxHash) {
        return new TransactionEvent(eventId, writtenTxHash, HUB,
                "0x" + "01".repeat(20), new BigDecimal("50000"), "ETH", BASE.plusSeconds(60));
    }

    /** 其余 5 笔大额转出，与目标交易一起触发 LARGE_AMOUNT 与 FAN_OUT。 */
    private static void ingestOtherFiveEvents(EventIngestionService ingestion) {
        for (int i = 2; i <= 6; i++) {
            IngestResult r = ingestion.ingest(new TransactionEvent(
                    "evt-" + i, "0xtx" + i, HUB,
                    "0x" + String.format("%040x", i),
                    new BigDecimal("50000"), "ETH", BASE.plusSeconds(i * 60L)));
            assertThat(r.accepted()).isTrue();
        }
    }

    /** 基准：目标交易以规范小写形式、evt-1 编号单线程灌入后得到的评分。 */
    private static RiskScoreResult baselineScore() {
        EventStore store = new EventStore();
        EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);
        ingestOtherFiveEvents(ingestion);
        assertThat(ingestion.ingest(tx1Variant("evt-1", TX1_CANONICAL)).accepted()).isTrue();
        return new RiskScoringService(store, new RuleSetProvider()).score(HUB);
    }

    private static RiskScoreResult scoreOf(EventStore store) {
        return new RiskScoringService(store, new RuleSetProvider()).score(HUB);
    }

    /**
     * 单线程、多种到达顺序：大写先到 / 小写先到 / 混写先到（首达都是同一个投递
     * 编号 evt-1，只是哈希写法不同），随后混入"换编号重投"（evt-re-2/evt-re-3）与
     * "不换编号重投"（evt-1 原样再来），断言结论与基准逐字段一致、拒因可区分。
     */
    @Test
    void sameTransactionDifferentCaseArrivesInAnyOrderYieldsIdenticalResult() {
        log.info("业务: 同一笔交易仅换大小写写法，按大写/小写/混写先到三种顺序单线程灌入，并混入换编号与不换编号重投");
        RiskScoreResult baseline = baselineScore();
        log.info("基准: score={} fingerprint={}", baseline.score(), baseline.eventSetFingerprint());

        // 首达始终是 evt-1，但三种顺序里它的哈希写法不同；后续投递是换了编号的重投。
        List<List<TransactionEvent>> orders = List.of(
                List.of(tx1Variant("evt-1", TX1_UPPER),
                        tx1Variant("evt-re-2", TX1_LOWER),
                        tx1Variant("evt-re-3", TX1_MIXED)),
                List.of(tx1Variant("evt-1", TX1_LOWER),
                        tx1Variant("evt-re-2", TX1_MIXED),
                        tx1Variant("evt-re-3", TX1_UPPER)),
                List.of(tx1Variant("evt-1", TX1_MIXED),
                        tx1Variant("evt-re-2", TX1_UPPER),
                        tx1Variant("evt-re-3", TX1_LOWER)));

        for (int orderIndex = 0; orderIndex < orders.size(); orderIndex++) {
            EventStore store = new EventStore();
            EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);
            ingestOtherFiveEvents(ingestion);

            List<IngestResult> firstRound = new ArrayList<>();
            for (TransactionEvent e : orders.get(orderIndex)) {
                firstRound.add(ingestion.ingest(e));
            }
            // 第二轮：把三条再原封不动投一遍——evt-1 不换编号，evt-re-2/evt-re-3 换编号。
            List<IngestResult> sameDeliveryReplays = new ArrayList<>();
            List<IngestResult> reNumberedReplays = new ArrayList<>();
            for (TransactionEvent e : orders.get(orderIndex)) {
                IngestResult again = ingestion.ingest(e);
                if (e.eventId().equals("evt-1")) {
                    sameDeliveryReplays.add(again);
                } else {
                    reNumberedReplays.add(again);
                }
            }

            long acceptedFirstRound = firstRound.stream().filter(IngestResult::accepted).count();
            log.info("顺序#{} (首达写法={}) 首达轮 accepted={}/3; 不换编号重投拒因={}; 换编号重投拒因={} (说明均指向 evt-1)",
                    orderIndex + 1, orders.get(orderIndex).get(0).txHash(), acceptedFirstRound,
                    sameDeliveryReplays.stream().map(IngestResult::rejectionReason).toList(),
                    reNumberedReplays.stream().map(IngestResult::rejectionReason).distinct().toList());

            // 同一笔交易无论几种写法、什么顺序，只接受一次
            assertThat(acceptedFirstRound).as("顺序#%s 应恰好接受一条", orderIndex + 1).isEqualTo(1);
            assertThat(store.size()).isEqualTo(6);
            // 不换投递编号 -> DUPLICATE_EVENT；换了投递编号（哪怕只换大小写）-> DUPLICATE_TRANSACTION
            assertThat(sameDeliveryReplays).singleElement().satisfies(r -> {
                assertThat(r.accepted()).isFalse();
                assertThat(r.rejectionReason()).isEqualTo(RejectionReason.DUPLICATE_EVENT);
            });
            assertThat(reNumberedReplays).hasSize(2).allSatisfy(r -> {
                assertThat(r.accepted()).isFalse();
                assertThat(r.rejectionReason()).isEqualTo(RejectionReason.DUPLICATE_TRANSACTION);
                assertThat(r.detail()).contains("evt-1");
            });
            // 两种拒因必须能分清楚
            assertThat(sameDeliveryReplays.get(0).rejectionReason())
                    .isNotEqualTo(reNumberedReplays.get(0).rejectionReason());

            RiskScoreResult scored = scoreOf(store);
            log.info("顺序#{} 评分: score={} fingerprint={} 与基准逐字段一致={}",
                    orderIndex + 1, scored.score(), scored.eventSetFingerprint(),
                    scored.equals(baseline));

            // 留存哈希必须是规范小写，不带任何首达写法的痕迹
            assertThat(store.snapshot())
                    .filteredOn(e -> e.eventId().equals("evt-1"))
                    .singleElement()
                    .extracting(TransactionEvent::txHash)
                    .isEqualTo(TX1_CANONICAL);
            // 分数、人读依据、结构化依据、指纹逐字段与基准一致
            assertThat(scored.score()).isEqualTo(baseline.score());
            assertThat(scored.evidence()).isEqualTo(baseline.evidence());
            assertThat(scored.ruleHits()).isEqualTo(baseline.ruleHits());
            assertThat(scored.eventSetFingerprint()).isEqualTo(baseline.eventSetFingerprint());
            // 结构化依据里该笔交易只能出现小写规范写法
            assertThat(scored.ruleHits()).allSatisfy(hit -> {
                assertThat(hit.txHashes()).contains(TX1_CANONICAL);
                assertThat(hit.txHashes()).allSatisfy(tx ->
                        assertThat(tx).isEqualTo(tx.toLowerCase(Locale.ROOT)));
            });
            assertThat(String.join("\n", scored.evidence())).doesNotContain("ABCDEF");
        }
    }

    /**
     * 并发（无预埋）：32 线程同时灌同一笔交易的不同写法、不同投递编号以及
     * 不换编号的重投。恰好接受一条，留存哈希被规范为小写；再用胜出的投递
     * 编号按规范写法单线程重放，评分结果必须与并发结果逐字段一致。
     */
    @Test
    void concurrentCaseWrappedFloodAcceptsExactlyOneWithCanonicalHash() throws InterruptedException {
        log.info("业务: 32 线程无预埋并发灌入同一笔交易的大写/小写/混写写法、不同投递编号及同编号重投");
        EventStore store = new EventStore();
        EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);
        ingestOtherFiveEvents(ingestion);

        List<TransactionEvent> flood = buildConcurrentFlood(32);
        List<IngestResult> results = runConcurrently(ingestion, flood, 32);

        long accepted = results.stream().filter(IngestResult::accepted).count();
        long duplicateEvents = results.stream()
                .filter(r -> !r.accepted() && r.rejectionReason() == RejectionReason.DUPLICATE_EVENT).count();
        long duplicateTransactions = results.stream()
                .filter(r -> !r.accepted()
                        && r.rejectionReason() == RejectionReason.DUPLICATE_TRANSACTION).count();
        log.info("并发(无预埋): total={} accepted={} duplicateEvent(同编号)={} duplicateTransaction(换编号)={} storeSize={}",
                flood.size(), accepted, duplicateEvents, duplicateTransactions, store.size());

        assertThat(accepted).isEqualTo(1);
        assertThat(store.size()).isEqualTo(6);
        assertThat(duplicateEvents).as("同编号重投必须产生 DUPLICATE_EVENT").isPositive();
        assertThat(duplicateTransactions).as("换编号重投必须产生 DUPLICATE_TRANSACTION").isPositive();
        assertThat(accepted + duplicateEvents + duplicateTransactions).isEqualTo(flood.size());

        TransactionEvent winner = store.snapshot().stream()
                .filter(e -> e.txHash().equals(TX1_CANONICAL)).findFirst().orElseThrow();
        log.info("并发胜出投递: eventId={} 留存哈希={}", winner.eventId(), winner.txHash());
        assertThat(winner.txHash()).isEqualTo(TX1_CANONICAL);

        RiskScoreResult concurrentScore = scoreOf(store);
        // 用胜出的投递编号 + 规范小写哈希单线程重放，结论必须逐字段一致
        EventStore replayStore = new EventStore();
        EventIngestionService replayIngestion = new EventIngestionService(new EventValidator(), replayStore);
        ingestOtherFiveEvents(replayIngestion);
        assertThat(replayIngestion.ingest(tx1Variant(winner.eventId(), TX1_CANONICAL)).accepted()).isTrue();
        RiskScoreResult sequentialEquivalent = scoreOf(replayStore);
        log.info("并发指纹={} 单线程等价重放指纹={} 一致={}",
                concurrentScore.eventSetFingerprint(), sequentialEquivalent.eventSetFingerprint(),
                concurrentScore.equals(sequentialEquivalent));
        assertThat(concurrentScore).isEqualTo(sequentialEquivalent);
    }

    /**
     * 并发（预埋一条已接受记录）：随后 32 线程并发重灌不同写法、不同编号，
     * 总接受数仍为 1，评分结果与单线程任意顺序基准逐字段一致。
     */
    @Test
    void concurrentRedeliveryAfterAcceptanceKeepsSingleCanonicalRecord() throws InterruptedException {
        log.info("业务: 先接受一笔，再由 32 线程并发重投其大写/小写/混写写法及不同投递编号");
        RiskScoreResult baseline = baselineScore();

        EventStore store = new EventStore();
        EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);
        ingestOtherFiveEvents(ingestion);
        assertThat(ingestion.ingest(tx1Variant("evt-1", TX1_LOWER)).accepted()).isTrue();

        List<TransactionEvent> flood = new ArrayList<>(buildConcurrentFlood(32));
        // 已接受的 evt-1 也由各线程原样换着写法重投 -> 必须判 DUPLICATE_EVENT
        for (int t = 0; t < 32; t++) {
            flood.add(tx1Variant("evt-1", t % 2 == 0 ? TX1_UPPER : TX1_MIXED));
        }
        List<IngestResult> results = runConcurrently(ingestion, flood, 32);

        long accepted = results.stream().filter(IngestResult::accepted).count();
        long duplicateEvents = results.stream()
                .filter(r -> !r.accepted() && r.rejectionReason() == RejectionReason.DUPLICATE_EVENT).count();
        long duplicateTransactions = results.stream()
                .filter(r -> !r.accepted()
                        && r.rejectionReason() == RejectionReason.DUPLICATE_TRANSACTION).count();
        log.info("并发(预埋): total={} 并发期 accepted={} duplicateEvent={} duplicateTransaction={} 总 storeSize={}",
                flood.size(), accepted, duplicateEvents, duplicateTransactions, store.size());

        assertThat(accepted).isZero();
        assertThat(store.size()).isEqualTo(6);
        assertThat(duplicateEvents).isPositive();
        assertThat(duplicateTransactions).isPositive();

        RiskScoreResult scored = scoreOf(store);
        log.info("预埋并发指纹={} 与单线程基准一致={}", scored.eventSetFingerprint(),
                scored.eventSetFingerprint().equals(baseline.eventSetFingerprint()));
        assertThat(scored).isEqualTo(baseline);
    }

    /** 对照组：哈希确实不同（不止大小写差异）仍是不同交易，各自计价，不被误并为一笔。 */
    @Test
    void genuinelyDifferentTxHashesRemainSeparateTransactions() {
        log.info("业务: 交易哈希末位不同（非大小写差异）必须视为不同交易，各自计价");
        EventStore store = new EventStore();
        EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);

        IngestResult a = ingestion.ingest(tx1Variant("evt-a", "0xABCDEF1"));
        IngestResult b = ingestion.ingest(tx1Variant("evt-b", "0xabcdef2")); // 末位不同：另一笔交易
        IngestResult bAgain = ingestion.ingest(tx1Variant("evt-c", "0xABCDEF2")); // 第二笔的大写重投

        log.info("不同哈希: a accepted={}, b accepted={}, b 的大写重投拒因={}, storeSize={}",
                a.accepted(), b.accepted(), bAgain.rejectionReason(), store.size());

        assertThat(a.accepted()).isTrue();
        assertThat(b.accepted()).isTrue();
        assertThat(bAgain.accepted()).isFalse();
        assertThat(bAgain.rejectionReason()).isEqualTo(RejectionReason.DUPLICATE_TRANSACTION);
        assertThat(bAgain.detail()).contains("evt-b");
        assertThat(store.size()).isEqualTo(2);
    }

    /**
     * 构造并发灌入数据：每个线程 6 份投递——三种写法各一份（编号带线程号）、
     * 同编号大写再投一份，外加两份所有线程共享编号的投递。
     * 既保证出现 DUPLICATE_EVENT，也保证出现 DUPLICATE_TRANSACTION。
     */
    private static List<TransactionEvent> buildConcurrentFlood(int threads) {
        List<TransactionEvent> flood = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String suffix = "-" + t;
            flood.add(tx1Variant("evt-concurrent" + suffix, TX1_UPPER));
            flood.add(tx1Variant("evt-concurrent" + suffix, TX1_LOWER));
            flood.add(tx1Variant("evt-concurrent" + suffix, TX1_MIXED));
            flood.add(tx1Variant("evt-concurrent" + suffix, TX1_UPPER)); // 不换编号只换写法的重投
            flood.add(tx1Variant("evt-shared-delivery", TX1_MIXED));     // 跨线程共享编号
            flood.add(tx1Variant("evt-shared-delivery", TX1_LOWER));
        }
        Collections.shuffle(flood);
        return flood;
    }

    private static List<IngestResult> runConcurrently(EventIngestionService ingestion,
                                                      List<TransactionEvent> flood, int threads)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<IngestResult> results = Collections.synchronizedList(new ArrayList<>());
        for (TransactionEvent e : flood) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                results.add(ingestion.ingest(e));
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return results;
    }
}
