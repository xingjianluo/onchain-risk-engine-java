package com.github.xingjianluo.onchainriskenginejava;

import com.github.xingjianluo.onchainriskenginejava.model.IngestionResult;
import com.github.xingjianluo.onchainriskenginejava.model.RejectReason;
import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;
import com.github.xingjianluo.onchainriskenginejava.store.InMemoryEventStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 幂等与并发：重复 eventId / txHash 必须被拒绝且原因可区分；
 * 高并发重复投递同一条事件，恰好只能有一次被接受（不会重复计价 / 状态错乱）。
 */
class InMemoryEventStoreTest {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventStoreTest.class);

    private static final String A = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private TransactionEvent tx(String eventId, String txHash) {
        return new TransactionEvent(eventId, txHash, 1_700_000_000_000L, A, B,
                new BigDecimal("10"), "ETH");
    }

    @Test
    @DisplayName("重复 eventId 与重复 txHash 原因可区分")
    void duplicatesRejectedWithDistinctReasons() {
        InMemoryEventStore store = new InMemoryEventStore();
        IngestionResult first = store.ingest(tx("e1", "t1"));
        log.info("首次摄入 => accepted={} reason={}", first.accepted(), first.reason());
        assertTrue(first.accepted());

        IngestionResult dupEvent = store.ingest(tx("e1", "t2"));
        log.info("重复 eventId 摄入 => accepted={} reason={}", dupEvent.accepted(), dupEvent.reason());
        assertFalse(dupEvent.accepted());
        assertEquals(RejectReason.DUPLICATE_EVENT_ID, dupEvent.reason());

        IngestionResult dupTx = store.ingest(tx("e2", "t1"));
        log.info("重复 txHash 摄入 => accepted={} reason={}", dupTx.accepted(), dupTx.reason());
        assertFalse(dupTx.accepted());
        assertEquals(RejectReason.DUPLICATE_TX_HASH, dupTx.reason());

        assertEquals(1, store.acceptedCount());
        assertEquals(2, store.rejectedCount());
    }

    @Test
    @DisplayName("16 线程并发重复投递同一事件，恰好一次接受，无重复计价")
    void concurrentDuplicateIngestExactlyOneAccepted() throws InterruptedException {
        InMemoryEventStore store = new InMemoryEventStore();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejectedEventId = new AtomicInteger();
        List<IngestionResult> results = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    IngestionResult r = store.ingest(tx("same-event", "same-tx"));
                    results.add(r);
                    if (r.accepted()) {
                        accepted.incrementAndGet();
                    } else if (r.reason() == RejectReason.DUPLICATE_EVENT_ID) {
                        rejectedEventId.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("并发摄入结果: 接受={} 因eventId拒绝={} 存储接受数={} 拒绝总数={}",
                accepted.get(), rejectedEventId.get(), store.acceptedCount(), store.rejectedCount());
        assertEquals(1, accepted.get(), "并发重复投递必须恰好接受一次");
        assertEquals(threads - 1, rejectedEventId.get());
        assertEquals(1, store.acceptedCount());
        assertEquals(threads - 1, store.rejectedCount());
        assertEquals(threads, results.size());
    }

    @Test
    @DisplayName("并发写入不同事件不丢失，快照大小与输入一致")
    void concurrentDistinctEventsNoLoss() throws InterruptedException {
        InMemoryEventStore store = new InMemoryEventStore();
        int n = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    store.ingest(tx("e-" + idx, "t-" + idx));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        pool.shutdown();
        log.info("并发写入 {} 条不同事件后 acceptedCount={}", n, store.acceptedCount());
        assertEquals(n, store.acceptedCount());
        assertEquals(n, store.snapshotEvents().size());
    }
}
