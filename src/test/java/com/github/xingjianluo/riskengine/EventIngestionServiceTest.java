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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摄入校验：每种非法输入都必须以可区分的原因被拒绝，绝不静默丢弃。
 */
class EventIngestionServiceTest {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionServiceTest.class);
    private static final String ALICE = "0x" + "aa".repeat(20);
    private static final String BOB = "0x" + "bb".repeat(20);

    private EventIngestionService service;
    private EventStore store;

    @BeforeEach
    void setUp() {
        store = new EventStore();
        service = new EventIngestionService(new EventValidator(), store);
    }

    private static TransactionEvent validEvent(String eventId) {
        return new TransactionEvent(eventId, "0xtx" + eventId, ALICE, BOB,
                new BigDecimal("100"), "ETH", Instant.parse("2026-09-01T00:00:00Z"));
    }

    private void logOutcome(TransactionEvent event, IngestResult result) {
        log.info("input={} -> accepted={} reason={} detail={}",
                event, result.accepted(), result.rejectionReason(), result.detail());
    }

    @Test
    void acceptsValidEvent() {
        TransactionEvent event = validEvent("e1");
        IngestResult result = service.ingest(event);
        logOutcome(event, result);
        assertThat(result.accepted()).isTrue();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateEventId() {
        TransactionEvent event = validEvent("dup-1");
        IngestResult first = service.ingest(event);
        IngestResult second = service.ingest(event);
        logOutcome(event, first);
        logOutcome(event, second);
        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isFalse();
        assertThat(second.rejectionReason()).isEqualTo(RejectionReason.DUPLICATE_EVENT);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void rejectsMissingFieldsWithDistinctReasons() {
        TransactionEvent noId = new TransactionEvent(null, "0xtx", ALICE, BOB,
                BigDecimal.ONE, "ETH", Instant.now());
        TransactionEvent noTx = new TransactionEvent("e2", " ", ALICE, BOB,
                BigDecimal.ONE, "ETH", Instant.now());
        TransactionEvent noAmount = new TransactionEvent("e3", "0xtx", ALICE, BOB,
                null, "ETH", Instant.now());
        TransactionEvent noAsset = new TransactionEvent("e4", "0xtx", ALICE, BOB,
                BigDecimal.ONE, "", Instant.now());
        TransactionEvent noTs = new TransactionEvent("e5", "0xtx", ALICE, BOB,
                BigDecimal.ONE, "ETH", null);

        assertThat(service.ingest(noId).rejectionReason()).isEqualTo(RejectionReason.MISSING_EVENT_ID);
        assertThat(service.ingest(noTx).rejectionReason()).isEqualTo(RejectionReason.MISSING_TX_HASH);
        assertThat(service.ingest(noAmount).rejectionReason()).isEqualTo(RejectionReason.MISSING_AMOUNT);
        assertThat(service.ingest(noAsset).rejectionReason()).isEqualTo(RejectionReason.MISSING_ASSET);
        assertThat(service.ingest(noTs).rejectionReason()).isEqualTo(RejectionReason.MISSING_TIMESTAMP);
        assertThat(store.size()).isZero();
    }

    @Test
    void rejectsInvalidAddresses() {
        TransactionEvent badFrom = new TransactionEvent("e6", "0xtx", "not-an-address", BOB,
                BigDecimal.ONE, "ETH", Instant.now());
        TransactionEvent badTo = new TransactionEvent("e7", "0xtx", ALICE, "0x123",
                BigDecimal.ONE, "ETH", Instant.now());
        IngestResult r1 = service.ingest(badFrom);
        IngestResult r2 = service.ingest(badTo);
        logOutcome(badFrom, r1);
        logOutcome(badTo, r2);
        assertThat(r1.rejectionReason()).isEqualTo(RejectionReason.INVALID_FROM_ADDRESS);
        assertThat(r2.rejectionReason()).isEqualTo(RejectionReason.INVALID_TO_ADDRESS);
    }

    @Test
    void rejectsOutOfRangeAmountsAndTimestamps() {
        TransactionEvent zero = new TransactionEvent("e8", "0xtx", ALICE, BOB,
                BigDecimal.ZERO, "ETH", Instant.now());
        TransactionEvent negative = new TransactionEvent("e9", "0xtx", ALICE, BOB,
                new BigDecimal("-5"), "ETH", Instant.now());
        TransactionEvent huge = new TransactionEvent("e10", "0xtx", ALICE, BOB,
                new BigDecimal("99999999999"), "ETH", Instant.now());
        TransactionEvent ancient = new TransactionEvent("e11", "0xtx", ALICE, BOB,
                BigDecimal.ONE, "ETH", Instant.parse("1990-01-01T00:00:00Z"));
        TransactionEvent future = new TransactionEvent("e12", "0xtx", ALICE, BOB,
                BigDecimal.ONE, "ETH", Instant.now().plusSeconds(7 * 24 * 3600));

        assertThat(service.ingest(zero).rejectionReason()).isEqualTo(RejectionReason.NON_POSITIVE_AMOUNT);
        assertThat(service.ingest(negative).rejectionReason()).isEqualTo(RejectionReason.NON_POSITIVE_AMOUNT);
        assertThat(service.ingest(huge).rejectionReason()).isEqualTo(RejectionReason.AMOUNT_OUT_OF_RANGE);
        assertThat(service.ingest(ancient).rejectionReason()).isEqualTo(RejectionReason.TIMESTAMP_OUT_OF_RANGE);
        assertThat(service.ingest(future).rejectionReason()).isEqualTo(RejectionReason.TIMESTAMP_OUT_OF_RANGE);
        assertThat(store.size()).isZero();
    }

    @Test
    void normalizesAddressCaseAndAsset() {
        String mixedCase = "0x" + "Aa".repeat(20);
        TransactionEvent event = new TransactionEvent("e13", "0xtx", mixedCase, BOB,
                new BigDecimal("100.00"), "eth", Instant.parse("2026-09-01T00:00:00Z"));
        IngestResult result = service.ingest(event);
        logOutcome(event, result);
        assertThat(result.accepted()).isTrue();
        TransactionEvent stored = store.snapshot().get(0);
        assertThat(stored.fromAddress()).isEqualTo(ALICE);
        assertThat(stored.asset()).isEqualTo("ETH");
    }
}
