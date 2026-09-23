package com.github.xingjianluo.onchainriskenginejava;

import com.github.xingjianluo.onchainriskenginejava.model.IngestionResult;
import com.github.xingjianluo.onchainriskenginejava.model.RejectReason;
import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;
import com.github.xingjianluo.onchainriskenginejava.validation.EventValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 非法输入必须被拦截，且每类问题返回可区分的 {@link RejectReason}。
 * 测试日志打印输入事件与判定原因。
 */
class EventValidatorTest {

    private static final Logger log = LoggerFactory.getLogger(EventValidatorTest.class);

    private final EventValidator validator = new EventValidator();

    private static final String A = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private TransactionEvent valid() {
        return new TransactionEvent("e1", "tx1", System.currentTimeMillis(), A, B,
                new BigDecimal("10"), "ETH");
    }

    private void logCase(String name, TransactionEvent e, IngestionResult result) {
        log.info("用例[{}] 输入 eventId={} txHash={} ts={} from={} to={} amount={} asset={} => accepted={} reason={} msg={}",
                name, e == null ? null : e.eventId(), e == null ? null : e.txHash(),
                e == null ? null : e.timestamp(), e == null ? null : e.from(),
                e == null ? null : e.to(),
                e == null || e.amount() == null ? null : e.amount().toPlainString(),
                e == null ? null : e.asset(),
                result.accepted(), result.reason(), result.message());
    }

    @Test
    @DisplayName("合法事件通过")
    void validEventAccepted() {
        TransactionEvent e = valid();
        IngestionResult r = validator.validate(e);
        logCase("合法事件", e, r);
        assertTrue(r.accepted());
        assertNull(r.reason());
    }

    @Test
    @DisplayName("缺失字段逐类可区分")
    void missingFieldsRejected() {
        assertRejected(new TransactionEvent(null, "t", 1L, A, B, BigDecimal.ONE, "ETH"),
                RejectReason.MISSING_FIELD);
        assertRejected(new TransactionEvent("e", " ", 1L, A, B, BigDecimal.ONE, "ETH"),
                RejectReason.MISSING_FIELD);
        assertRejected(new TransactionEvent("e", "t", 1L, A, B, null, "ETH"),
                RejectReason.MISSING_FIELD);
        assertRejected(new TransactionEvent("e", "t", 1L, A, B, BigDecimal.ONE, null),
                RejectReason.MISSING_FIELD);
        assertRejected(new TransactionEvent("e", "t", 0L, A, B, BigDecimal.ONE, "ETH"),
                RejectReason.MISSING_FIELD);
    }

    @Test
    @DisplayName("非法地址、自发自收、金额/时间越界原因各自独立")
    void invalidInputsRejectedWithDistinctReasons() {
        assertRejected(new TransactionEvent("e", "t", 1L, "0x123", B, BigDecimal.ONE, "ETH"),
                RejectReason.INVALID_ADDRESS);
        assertRejected(new TransactionEvent("e", "t", 1L, A, "zzzz", BigDecimal.ONE, "ETH"),
                RejectReason.INVALID_ADDRESS);
        assertRejected(new TransactionEvent("e", "t", 1L, A, A, BigDecimal.ONE, "ETH"),
                RejectReason.SELF_TRANSACTION);
        assertRejected(new TransactionEvent("e", "t", 1L, A, B, BigDecimal.ZERO, "ETH"),
                RejectReason.AMOUNT_OUT_OF_RANGE);
        assertRejected(new TransactionEvent("e", "t", 1L, A, B, new BigDecimal("-5"), "ETH"),
                RejectReason.AMOUNT_OUT_OF_RANGE);
        assertRejected(new TransactionEvent("e", "t", 1L, A, B,
                EventValidator.MAX_AMOUNT.add(BigDecimal.ONE), "ETH"), RejectReason.AMOUNT_OUT_OF_RANGE);
        assertRejected(new TransactionEvent("e", "t", System.currentTimeMillis() + 10L * 86400_000L,
                A, B, BigDecimal.ONE, "ETH"), RejectReason.TIMESTAMP_OUT_OF_RANGE);
    }

    private void assertRejected(TransactionEvent e, RejectReason expected) {
        IngestionResult r = validator.validate(e);
        logCase(expected.name(), e, r);
        assertFalse(r.accepted());
        assertEquals(expected, r.reason());
        assertTrue(r.message().length() > 0, "拒绝必须带可读原因，不能静默丢弃");
    }
}
