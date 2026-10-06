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
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务场景：同一笔链上交易被上游反复重投——有的只把交易哈希换了大小写写法，
 * 有的同时换了投递编号（eventId），有的连投递编号都不换。
 * 无论这些版本按什么顺序到达、被重复投递多少次、是否被几十个线程并发灌入，
 * 业务上只计价一笔，且评分里显示的交易哈希、结构化判定依据（ruleHits）
 * 与事件集合指纹必须逐字段完全一致。
 *
 * <p>留存口径：同一笔交易的多次投递中，系统留存"投递编号字典序最小"的那一条，
 * 交易哈希统一为小写——因此留存内容只取决于投递集合本身，与到达顺序无关。</p>
 */
class TxHashCaseRedeliveryDeterminismTest {

    private static final Logger log = LoggerFactory.getLogger(TxHashCaseRedeliveryDeterminismTest.class);
    private static final String ALICE = "0x" + "aa".repeat(20);
    private static final String BOB = "0x" + "bb".repeat(20);
    private static final Instant TS = Instant.parse("2026-09-01T00:00:00Z");

    /** 同一笔链上交易的规范（小写）哈希。 */
    private static final String TX_LOWER = "0x" + "ab12cd34".repeat(8);
    /** 同一笔交易的大写写法。 */
    private static final String TX_UPPER = TX_LOWER.toUpperCase(Locale.ROOT).replace("0X", "0x");
    /** 同一笔交易的混合大小写写法。 */
    private static final String TX_MIXED = mixCase(TX_LOWER);

    /** 字典序最小的投递编号——规范留存应当收敛到它。 */
    private static final String CANONICAL_EVENT_ID = "delivery-1";

    private static String mixCase(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(i % 2 == 0 ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return b.toString();
    }

    /** 同一笔交易的一笔投递：指定投递编号 + 哈希大小写写法。 */
    private static TransactionEvent delivery(String eventId, String txHash) {
        return new TransactionEvent(eventId, txHash, ALICE, BOB,
                new BigDecimal("50000"), "ETH", TS);
    }

    /**
     * 构造同一笔交易的一批重投版本：6 个不同投递编号（delivery-1..6），
     * 各自搭配大写/小写/混合大小写的交易哈希写法。
     */
    private static List<TransactionEvent> redeliveriesOfSameTx() {
        String[] casings = {TX_LOWER, TX_UPPER, TX_MIXED, TX_UPPER, TX_LOWER, TX_MIXED};
        List<TransactionEvent> deliveries = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            deliveries.add(delivery("delivery-" + (i + 1), casings[i]));
        }
        return deliveries;
    }

    private static RiskScoreResult scoreOf(EventStore store) {
        return new RiskScoringService(store, new RuleSetProvider()).score(ALICE);
    }

    @Test
    void retainedContentIsIdenticalAcrossArrivalOrders() {
        List<TransactionEvent> deliveries = redeliveriesOfSameTx();
        log.info("业务覆盖：同一笔交易 {} 被换成 {} 种投递编号、3 种大小写写法重投，"
                + "按多种到达顺序逐条灌入，验证只计价一笔且留存内容一致", TX_LOWER, deliveries.size());

        RiskScoreResult baseline = null;
        String baselineEvidenceJoined = null;
        // 多种到达顺序：正序、逆序、以及 4 种确定性乱序
        for (int seed = 0; seed < 6; seed++) {
            List<TransactionEvent> order = new ArrayList<>(deliveries);
            if (seed == 1) {
                Collections.reverse(order);
            } else if (seed > 1) {
                Collections.shuffle(order, new Random(seed * 7919L));
            }
            // 每种顺序里都混入"连投递编号都不换"的重复投递（该顺序的第一条再送一遍，换个大小写）
            TransactionEvent first = order.get(0);
            String recasedHash = first.txHash().equals(TX_UPPER) ? TX_LOWER : TX_UPPER;
            order.add(delivery(first.eventId(), recasedHash));

            EventStore store = new EventStore();
            EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);
            List<IngestResult> results = new ArrayList<>();
            for (TransactionEvent e : order) {
                results.add(ingestion.ingest(e));
            }

            long accepted = results.stream().filter(IngestResult::accepted).count();
            long eventDups = results.stream()
                    .filter(r -> !r.accepted() && r.rejectionReason() == RejectionReason.DUPLICATE_EVENT)
                    .count();
            long txDups = results.stream()
                    .filter(r -> !r.accepted() && r.rejectionReason() == RejectionReason.DUPLICATE_TRANSACTION)
                    .count();
            log.info("顺序#{}: 到达={} 接受={} 投递编号重复(DUPLICATE_EVENT)={} 交易重复(DUPLICATE_TRANSACTION)={}",
                    seed, order.size(), accepted, eventDups, txDups);

            // 只接受一条；两种拒因都出现且可区分
            assertThat(accepted).isEqualTo(1);
            assertThat(eventDups).isEqualTo(1);
            assertThat(txDups).isEqualTo(order.size() - 2);
            assertThat(RejectionReason.DUPLICATE_EVENT).isNotEqualTo(RejectionReason.DUPLICATE_TRANSACTION);
            assertThat(store.size()).isEqualTo(1);

            // 留存记录与大小写、到达顺序无关：规范投递编号 + 小写哈希
            TransactionEvent retained = store.snapshot().get(0);
            log.info("顺序#{}: 留存 eventId={} txHash={}", seed, retained.eventId(), retained.txHash());
            assertThat(retained.eventId()).isEqualTo(CANONICAL_EVENT_ID);
            assertThat(retained.txHash()).isEqualTo(TX_LOWER);

            RiskScoreResult result = scoreOf(store);
            if (baseline == null) {
                baseline = result;
                baselineEvidenceJoined = String.join("\n", result.evidence());
                log.info("基线（顺序#0）: score={} fingerprint={} ruleHits={}",
                        result.score(), result.eventSetFingerprint(), result.ruleHits());
            } else {
                // 指纹、结构化判定依据、人读依据链逐字段一致
                assertThat(result.eventSetFingerprint()).isEqualTo(baseline.eventSetFingerprint());
                assertThat(result.ruleHits()).isEqualTo(baseline.ruleHits());
                assertThat(result.evidence()).isEqualTo(baseline.evidence());
                assertThat(result.score()).isEqualTo(baseline.score());
            }
        }
        // 评分里显示的交易哈希必须是小写规范写法，与先到大写/先到小写无关
        assertThat(baseline.ruleHits().get(0).txHashes()).containsExactly(TX_LOWER);
        assertThat(baselineEvidenceJoined).contains("tx=" + TX_LOWER);
        log.info("6 种到达顺序的指纹与结构化依据逐字段一致，评分显示的交易哈希={}", TX_LOWER);
    }

    @Test
    void concurrentRedeliveryMatchesSequentialResultFieldByField() throws InterruptedException {
        // 单线程基线：同一批重投版本按任意顺序逐条灌入
        EventStore seqStore = new EventStore();
        EventIngestionService seqIngestion = new EventIngestionService(new EventValidator(), seqStore);
        List<TransactionEvent> sequential = redeliveriesOfSameTx();
        Collections.shuffle(sequential, new Random(42L));
        sequential.forEach(seqIngestion::ingest);
        RiskScoreResult baseline = scoreOf(seqStore);
        log.info("单线程基线: score={} fingerprint={} ruleHits={}",
                baseline.score(), baseline.eventSetFingerprint(), baseline.ruleHits());

        // 并发灌入：每个投递编号 6 份副本（大小写写法各不相同），36 个线程同时灌
        List<TransactionEvent> stream = new ArrayList<>();
        String[] casings = {TX_LOWER, TX_UPPER, TX_MIXED, TX_UPPER, TX_MIXED, TX_LOWER};
        for (int id = 1; id <= 6; id++) {
            for (int copy = 0; copy < 6; copy++) {
                stream.add(delivery("delivery-" + id, casings[copy]));
            }
        }
        Collections.shuffle(stream, new Random(7L));
        log.info("业务覆盖：{} 个线程并发灌入同一笔交易的 {} 份重投（6 种投递编号 x 6 种大小写副本）",
                stream.size(), stream.size());

        EventStore store = new EventStore();
        EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);
        ExecutorService pool = Executors.newFixedThreadPool(stream.size());
        CountDownLatch start = new CountDownLatch(1);
        List<IngestResult> results = Collections.synchronizedList(new ArrayList<>());
        for (TransactionEvent e : stream) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                results.add(ingestion.ingest(e));
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long accepted = results.stream().filter(IngestResult::accepted).count();
        long eventDups = results.stream()
                .filter(r -> !r.accepted() && r.rejectionReason() == RejectionReason.DUPLICATE_EVENT)
                .count();
        long txDups = results.stream()
                .filter(r -> !r.accepted() && r.rejectionReason() == RejectionReason.DUPLICATE_TRANSACTION)
                .count();
        long otherRejections = results.stream()
                .filter(r -> !r.accepted()
                        && r.rejectionReason() != RejectionReason.DUPLICATE_EVENT
                        && r.rejectionReason() != RejectionReason.DUPLICATE_TRANSACTION)
                .count();
        log.info("并发结果: 接受={} 投递编号重复={} 交易重复={} 其他拒绝={} 存储大小={}",
                accepted, eventDups, txDups, otherRejections, store.size());

        // 只接受一条；两种拒因都出现（被接受的那条编号有重复副本 -> DUPLICATE_EVENT；
        // 其余编号是换号重投 -> DUPLICATE_TRANSACTION），无其他拒绝
        assertThat(accepted).isEqualTo(1);
        assertThat(eventDups).isGreaterThanOrEqualTo(1);
        assertThat(txDups).isGreaterThanOrEqualTo(1);
        assertThat(otherRejections).isZero();
        assertThat(store.size()).isEqualTo(1);

        // 并发最终结果与单线程逐条灌入逐字段一致
        RiskScoreResult concurrent = scoreOf(store);
        log.info("并发评分: score={} fingerprint={} 与单线程一致={}",
                concurrent.score(), concurrent.eventSetFingerprint(),
                concurrent.eventSetFingerprint().equals(baseline.eventSetFingerprint()));
        assertThat(concurrent.eventSetFingerprint()).isEqualTo(baseline.eventSetFingerprint());
        assertThat(concurrent.ruleHits()).isEqualTo(baseline.ruleHits());
        assertThat(concurrent.evidence()).isEqualTo(baseline.evidence());
        assertThat(concurrent.score()).isEqualTo(baseline.score());
        assertThat(store.snapshot().get(0).eventId()).isEqualTo(CANONICAL_EVENT_ID);
        assertThat(store.snapshot().get(0).txHash()).isEqualTo(TX_LOWER);
    }

    @Test
    void differentTxHashesAreStillDistinctTransactions() {
        // 对照组：哈希内容不同（不只是大小写不同）的是不同交易，各自计价
        EventStore store = new EventStore();
        EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);
        String otherTx = "0x" + "ab12cd35".repeat(8); // 与 TX_LOWER 仅一位数字不同

        IngestResult first = ingestion.ingest(delivery("delivery-1", TX_UPPER));
        IngestResult second = ingestion.ingest(delivery("delivery-2", otherTx));
        log.info("对照组: 哈希仅一位不同 -> 第一笔 accepted={} 第二笔 accepted={} 存储大小={}",
                first.accepted(), second.accepted(), store.size());

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isTrue();
        assertThat(store.size()).isEqualTo(2);
    }
}
