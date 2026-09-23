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
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发摄入 + 重复投递 + 乱序到达：
 * 每个 eventId 只计价一次，最终评分与摄入顺序无关。
 */
class ConcurrentIngestionTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentIngestionTest.class);
    private static final int UNIQUE_EVENTS = 200;
    private static final int DUPLICATE_COPIES = 3;
    private static final String HUB = "0x" + "aa".repeat(20);

    private static List<TransactionEvent> buildEvents() {
        List<TransactionEvent> events = new ArrayList<>();
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 0; i < UNIQUE_EVENTS; i++) {
            String counterparty = "0x" + String.format("%040x", i + 1);
            events.add(new TransactionEvent(
                    "evt-" + i, "0xtx" + i, HUB, counterparty,
                    new BigDecimal("20000"), "ETH", base.plusSeconds(i)));
        }
        return events;
    }

    @Test
    void concurrentIngestionWithDuplicatesAndOutOfOrderIsIdempotentAndDeterministic()
            throws InterruptedException {
        List<TransactionEvent> unique = buildEvents();

        // 构造乱序 + 重复投递的事件流
        List<TransactionEvent> stream = new ArrayList<>();
        for (int copy = 0; copy < DUPLICATE_COPIES; copy++) {
            List<TransactionEvent> shuffled = new ArrayList<>(unique);
            Collections.shuffle(shuffled, new Random(copy));
            stream.addAll(shuffled);
        }
        log.info("ingesting {} events ({} unique x {} copies) concurrently", stream.size(),
                UNIQUE_EVENTS, DUPLICATE_COPIES);

        EventStore store = new EventStore();
        EventIngestionService ingestion = new EventIngestionService(new EventValidator(), store);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<IngestResult> results = Collections.synchronizedList(new ArrayList<>());
        for (TransactionEvent event : stream) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                results.add(ingestion.ingest(event));
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long accepted = results.stream().filter(IngestResult::accepted).count();
        long duplicates = results.stream()
                .filter(r -> !r.accepted() && r.rejectionReason() == RejectionReason.DUPLICATE_EVENT)
                .count();
        log.info("accepted={} duplicates={} storeSize={}", accepted, duplicates, store.size());

        assertThat(accepted).isEqualTo(UNIQUE_EVENTS);
        assertThat(duplicates).isEqualTo((long) UNIQUE_EVENTS * (DUPLICATE_COPIES - 1));
        assertThat(store.size()).isEqualTo(UNIQUE_EVENTS);

        // 评分确定性：同一批数据换 5 种摄入顺序重建，得分必须一致
        RiskScoringService scoring = new RiskScoringService(store, new RuleSetProvider());
        RiskScoreResult baseline = scoring.score(HUB);
        log.info("baseline score={} evidence={}", baseline.score(), baseline.evidence());

        for (int seed = 1; seed <= 5; seed++) {
            EventStore other = new EventStore();
            EventIngestionService otherIngestion =
                    new EventIngestionService(new EventValidator(), other);
            List<TransactionEvent> reordered = new ArrayList<>(unique);
            Collections.shuffle(reordered, new Random(seed * 1000L));
            reordered.forEach(otherIngestion::ingest);

            RiskScoreResult rescored =
                    new RiskScoringService(other, new RuleSetProvider()).score(HUB);
            log.info("seed={} score={}", seed, rescored.score());
            assertThat(rescored.score()).isEqualTo(baseline.score());
            assertThat(rescored.evidence()).isEqualTo(baseline.evidence());
        }

        // 200 个不同对手方 -> FAN_OUT 命中；每笔 20000 >= 10000 -> 大额命中，封顶 100
        assertThat(baseline.score()).isEqualTo(100);
        assertThat(baseline.evidence()).anyMatch(e -> e.startsWith("FAN_OUT"));
        assertThat(baseline.evidence()).anyMatch(e -> e.startsWith("LARGE_AMOUNT"));
    }
}
