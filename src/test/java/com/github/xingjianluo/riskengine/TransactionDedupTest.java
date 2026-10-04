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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务场景：同一笔链上交易被上游换了投递编号（eventId）重复推送，
 * 甚至几十个线程同时灌入——业务上只能计价一次。
 *
 * <p>断言要点：
 * 1) 同一 txHash 无论换多少个 eventId、地址大小写怎么写，只接受一次；
 * 2) "投递编号重复"（DUPLICATE_EVENT）与"交易本身重复"（DUPLICATE_TRANSACTION）
 *    是可区分的两种拒因，且拒绝说明里能定位到首次接受的事件；
 * 3) 并发灌入下接受数恰好为 1，不多不少。</p>
 */
class TransactionDedupTest {

    private static final Logger log = LoggerFactory.getLogger(TransactionDedupTest.class);
    private static final String ALICE = "0x" + "aa".repeat(20);
    private static final String BOB_MIXED_CASE = "0x" + "Bb".repeat(20);
    private static final String BOB = BOB_MIXED_CASE.toLowerCase();
    private static final Instant TS = Instant.parse("2026-09-01T00:00:00Z");

    private EventStore store;
    private EventIngestionService service;

    @BeforeEach
    void setUp() {
        store = new EventStore();
        service = new EventIngestionService(new EventValidator(), store);
    }

    private static TransactionEvent event(String eventId, String txHash) {
        // 故意使用混合大小写地址，验证归一化后仍按同一笔交易去重
        return new TransactionEvent(eventId, txHash, ALICE, BOB_MIXED_CASE,
                new BigDecimal("100"), "ETH", TS);
    }

    @Test
    void sameTxHashWithDifferentEventIdCountsOnceAndRejectionReasonsAreDistinct() {
        IngestResult first = service.ingest(event("delivery-1", "0xTXABC"));
        IngestResult sameDelivery = service.ingest(event("delivery-1", "0xTXABC"));
        IngestResult redelivered = service.ingest(event("delivery-2", "0xtxabc"));
        IngestResult redeliveredAgain = service.ingest(event("delivery-3", "0xTxAbC"));

        log.info("first            -> accepted={}", first.accepted());
        log.info("same eventId     -> reason={} detail={}", sameDelivery.rejectionReason(), sameDelivery.detail());
        log.info("new eventId dup  -> reason={} detail={}", redelivered.rejectionReason(), redelivered.detail());
        log.info("mixed-case dup   -> reason={} detail={}", redeliveredAgain.rejectionReason(), redeliveredAgain.detail());

        assertThat(first.accepted()).isTrue();
        // 同一投递编号：DUPLICATE_EVENT
        assertThat(sameDelivery.accepted()).isFalse();
        assertThat(sameDelivery.rejectionReason()).isEqualTo(RejectionReason.DUPLICATE_EVENT);
        // 换了投递编号但 txHash 相同（忽略大小写）：DUPLICATE_TRANSACTION，且说明指向首次事件
        assertThat(redelivered.rejectionReason()).isEqualTo(RejectionReason.DUPLICATE_TRANSACTION);
        assertThat(redelivered.detail()).contains("delivery-1");
        assertThat(redeliveredAgain.rejectionReason()).isEqualTo(RejectionReason.DUPLICATE_TRANSACTION);
        // 两种拒因必须可区分
        assertThat(sameDelivery.rejectionReason()).isNotEqualTo(redelivered.rejectionReason());
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void concurrentRedeliveryOfSameTransactionCountsExactlyOnce() throws InterruptedException {
        int threads = 32;
        int copiesPerThread = 20;
        int total = threads * copiesPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<IngestResult> results = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < total; i++) {
            String eventId = "redelivery-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // 全部线程灌同一笔链上交易（同一 txHash），只是投递编号不同
                results.add(service.ingest(event(eventId, "0xDEADBEEF")));
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long accepted = results.stream().filter(IngestResult::accepted).count();
        long txDuplicates = results.stream()
                .filter(r -> !r.accepted() && r.rejectionReason() == RejectionReason.DUPLICATE_TRANSACTION)
                .count();
        long otherRejections = results.stream()
                .filter(r -> !r.accepted() && r.rejectionReason() != RejectionReason.DUPLICATE_TRANSACTION)
                .count();
        log.info("concurrent redelivery: total={} accepted={} duplicateTransaction={} otherRejections={} storeSize={}",
                total, accepted, txDuplicates, otherRejections, store.size());

        assertThat(accepted).isEqualTo(1);
        assertThat(txDuplicates).isEqualTo(total - 1L);
        assertThat(otherRejections).isZero();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void distinctTransactionsWithDistinctTxHashesAreAllAccepted() {
        // 对照组：不同 txHash 是不同交易，必须全部计价
        for (int i = 0; i < 10; i++) {
            IngestResult result = service.ingest(event("evt-" + i, "0xtx" + i));
            assertThat(result.accepted()).as("event evt-%s should be accepted", i).isTrue();
        }
        log.info("distinct transactions accepted, storeSize={}", store.size());
        assertThat(store.size()).isEqualTo(10);
    }
}
