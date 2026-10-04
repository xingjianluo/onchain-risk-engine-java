package com.github.xingjianluo.riskengine;

import com.github.xingjianluo.riskengine.ingest.EventIngestionService;
import com.github.xingjianluo.riskengine.ingest.EventValidator;
import com.github.xingjianluo.riskengine.model.IngestResult;
import com.github.xingjianluo.riskengine.model.RejectionReason;
import com.github.xingjianluo.riskengine.model.TransactionEvent;
import com.github.xingjianluo.riskengine.store.EventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交易级去重：业务上的"同一笔链上交易"（txHash 相同）无论换多少个投递编号、
 * 地址大小写怎么写、多少线程同时灌入，都只计价一次；
 * 且"投递编号重复"（DUPLICATE_EVENT）与"交易本身重复"（DUPLICATE_TRANSACTION）
 * 必须给出可区分的拒绝说明。
 */
class TransactionLevelDedupTest {

    private static final Logger log = LoggerFactory.getLogger(TransactionLevelDedupTest.class);
    private static final String ALICE = "0x" + "aa".repeat(20);
    private static final String BOB = "0x" + "bb".repeat(20);
    private static final Instant TS = Instant.parse("2026-09-01T00:00:00Z");

    private EventStore store;
    private EventIngestionService service;

    @BeforeEach
    void setUp() {
        store = new EventStore();
        service = new EventIngestionService(new EventValidator(), store);
    }

    private static TransactionEvent event(String eventId, String txHash) {
        return new TransactionEvent(eventId, txHash, ALICE, BOB,
                new BigDecimal("100"), "ETH", TS);
    }

    @Test
    void sameTxHashWithDifferentEventIdCountsOnlyOnce() {
        IngestResult first = service.ingest(event("delivery-1", "0xtxABC"));
        IngestResult second = service.ingest(event("delivery-2", "0xtxABC"));
        IngestResult third = service.ingest(event("delivery-3", "0xtxabc")); // txHash 大小写变体
        log.info("first={} second(reason={}) third(reason={})",
                first.accepted(), second.rejectionReason(), third.rejectionReason());

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isFalse();
        assertThat(second.rejectionReason()).isEqualTo(RejectionReason.DUPLICATE_TRANSACTION);
        assertThat(third.rejectionReason()).isEqualTo(RejectionReason.DUPLICATE_TRANSACTION);
        // 两种重复的拒绝说明必须可区分
        assertThat(second.detail()).contains("txHash").isNotEqualTo(
                "eventId already accepted: delivery-2");
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void duplicateEventIdAndDuplicateTransactionAreDistinctReasons() {
        service.ingest(event("delivery-1", "0xtx1"));

        IngestResult sameDelivery = service.ingest(event("delivery-1", "0xtx1"));
        IngestResult sameTxNewDelivery = service.ingest(event("delivery-2", "0xtx1"));
        log.info("same eventId -> {} / {}; same txHash new eventId -> {} / {}",
                sameDelivery.rejectionReason(), sameDelivery.detail(),
                sameTxNewDelivery.rejectionReason(), sameTxNewDelivery.detail());

        assertThat(sameDelivery.rejectionReason()).isEqualTo(RejectionReason.DUPLICATE_EVENT);
        assertThat(sameDelivery.detail()).contains("eventId");
        assertThat(sameTxNewDelivery.rejectionReason()).isEqualTo(RejectionReason.DUPLICATE_TRANSACTION);
        assertThat(sameTxNewDelivery.detail()).contains("txHash");
        assertThat(sameDelivery.rejectionReason()).isNotEqualTo(sameTxNewDelivery.rejectionReason());
    }

    @Test
    void addressCaseVariantsOfSameTransactionStillDeduplicate() {
        String mixedAlice = "0x" + "Aa".repeat(20);
        String mixedBob = "0x" + "bB".repeat(20);
        IngestResult first = service.ingest(new TransactionEvent(
                "d-1", "0xtxCase", ALICE, BOB, new BigDecimal("100"), "ETH", TS));
        // 同一笔交易：txHash 相同，只是地址大小写写法不同、投递编号不同
        IngestResult second = service.ingest(new TransactionEvent(
                "d-2", "0xtxCase", mixedAlice, mixedBob, new BigDecimal("100"), "eth", TS));
        log.info("case-variant redelivery -> reason={}", second.rejectionReason());

        assertThat(first.accepted()).isTrue();
        assertThat(second.rejectionReason()).isEqualTo(RejectionReason.DUPLICATE_TRANSACTION);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void concurrentFloodOfSameTransactionCountsExactlyOnce() throws InterruptedException {
        int threads = 32;
        int copiesPerThread = 20; // 每个线程灌 20 份同一笔交易的不同投递
        int total = threads * copiesPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<IngestResult> results = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < copiesPerThread; i++) {
                    // 每份都是不同的投递编号，但交易哈希相同
                    results.add(service.ingest(event("t" + threadId + "-d" + i, "0xtxFLOOD")));
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Map<Boolean, List<IngestResult>> byAccepted = results.stream()
                .collect(Collectors.partitioningBy(IngestResult::accepted));
        long accepted = byAccepted.get(true).size();
        long txDuplicates = byAccepted.get(false).stream()
                .filter(r -> r.rejectionReason() == RejectionReason.DUPLICATE_TRANSACTION).count();
        long eventDuplicates = byAccepted.get(false).stream()
                .filter(r -> r.rejectionReason() == RejectionReason.DUPLICATE_EVENT).count();
        log.info("concurrent flood: threads={} total={} accepted={} txDuplicates={} eventDuplicates={} storeSize={}",
                threads, total, accepted, txDuplicates, eventDuplicates, store.size());

        assertThat(results).hasSize(total);
        assertThat(accepted).isEqualTo(1);
        assertThat(txDuplicates).isEqualTo(total - 1);
        assertThat(eventDuplicates).isZero();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void concurrentMixedDuplicatesKeepReasonsDistinct() throws InterruptedException {
        // 先顺序落库两笔交易，确保归属确定：dupId 拥有 0xtxA，txB-0 拥有 0xtxB
        assertThat(service.ingest(event("dupId", "0xtxA")).accepted()).isTrue();
        assertThat(service.ingest(event("txB-0", "0xtxB")).accepted()).isTrue();

        // 并发灌入三类重复：同投递编号重复 / 同交易换投递编号 / 另一笔交易换投递编号
        List<TransactionEvent> stream = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            stream.add(event("dupId", "0xtxA"));          // 同一投递编号反复投递 -> DUPLICATE_EVENT
            stream.add(event("txA-copy-" + i, "0xtxA"));  // 同一交易换投递编号 -> DUPLICATE_TRANSACTION
            stream.add(event("txB-" + (i + 1), "0xtxB")); // 另一笔交易换投递编号 -> DUPLICATE_TRANSACTION
        }
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<IngestResult> results = new ConcurrentLinkedQueue<>();
        for (TransactionEvent e : stream) {
            pool.submit(() -> {
                await(start);
                results.add(service.ingest(e));
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long accepted = results.stream().filter(IngestResult::accepted).count();
        long eventDups = results.stream()
                .filter(r -> r.rejectionReason() == RejectionReason.DUPLICATE_EVENT).count();
        long txDups = results.stream()
                .filter(r -> r.rejectionReason() == RejectionReason.DUPLICATE_TRANSACTION).count();
        log.info("mixed duplicates: total={} accepted={} eventDups={} txDups={} storeSize={}",
                results.size(), accepted, eventDups, txDups, store.size());

        // 两笔交易归属已定，并发灌入的 150 条全部应被拒绝
        assertThat(accepted).isZero();
        // "dupId" 已在库中：50 次重投全部判为投递编号重复
        assertThat(eventDups).isEqualTo(50);
        // 其余 100 条全部是交易级重复
        assertThat(txDups).isEqualTo(100);
        assertThat(store.size()).isEqualTo(2);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
