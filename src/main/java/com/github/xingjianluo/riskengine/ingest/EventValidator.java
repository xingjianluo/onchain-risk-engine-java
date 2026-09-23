package com.github.xingjianluo.riskengine.ingest;

import com.github.xingjianluo.riskengine.model.RejectionReason;
import com.github.xingjianluo.riskengine.model.TransactionEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 事件合法性校验：非法地址、缺失字段、超范围数据在此被拒绝，
 * 每种拒绝都有可区分的原因，绝不静默丢弃。
 */
@Component
public class EventValidator {

    /** 以太坊风格地址：0x 前缀 + 40 位十六进制。 */
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    /** 单笔金额上限，防止异常大数污染统计。 */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");

    /** 可接受的时间范围：[2009-01-03, 现在 + 1 天容忍]。 */
    private static final Instant MIN_TIMESTAMP = Instant.parse("2009-01-03T00:00:00Z");
    private static final long MAX_FUTURE_SKEW_SECONDS = 24 * 3600;

    public record ValidationOutcome(boolean valid, RejectionReason reason, String detail) {
        public static ValidationOutcome ok() {
            return new ValidationOutcome(true, null, "OK");
        }

        public static ValidationOutcome invalid(RejectionReason reason, String detail) {
            return new ValidationOutcome(false, reason, detail);
        }
    }

    public ValidationOutcome validate(TransactionEvent event) {
        if (event == null) {
            return ValidationOutcome.invalid(RejectionReason.MISSING_EVENT_ID, "event is null");
        }
        if (isBlank(event.eventId())) {
            return ValidationOutcome.invalid(RejectionReason.MISSING_EVENT_ID, "eventId is blank");
        }
        if (isBlank(event.txHash())) {
            return ValidationOutcome.invalid(RejectionReason.MISSING_TX_HASH, "txHash is blank");
        }
        if (!isValidAddress(event.fromAddress())) {
            return ValidationOutcome.invalid(RejectionReason.INVALID_FROM_ADDRESS,
                    "fromAddress is not a valid 0x-hex address: " + event.fromAddress());
        }
        if (!isValidAddress(event.toAddress())) {
            return ValidationOutcome.invalid(RejectionReason.INVALID_TO_ADDRESS,
                    "toAddress is not a valid 0x-hex address: " + event.toAddress());
        }
        if (event.amount() == null) {
            return ValidationOutcome.invalid(RejectionReason.MISSING_AMOUNT, "amount is null");
        }
        if (event.amount().signum() <= 0) {
            return ValidationOutcome.invalid(RejectionReason.NON_POSITIVE_AMOUNT,
                    "amount must be positive: " + event.amount());
        }
        if (event.amount().compareTo(MAX_AMOUNT) > 0) {
            return ValidationOutcome.invalid(RejectionReason.AMOUNT_OUT_OF_RANGE,
                    "amount exceeds max " + MAX_AMOUNT + ": " + event.amount());
        }
        if (isBlank(event.asset())) {
            return ValidationOutcome.invalid(RejectionReason.MISSING_ASSET, "asset is blank");
        }
        if (event.timestamp() == null) {
            return ValidationOutcome.invalid(RejectionReason.MISSING_TIMESTAMP, "timestamp is null");
        }
        if (event.timestamp().isBefore(MIN_TIMESTAMP)
                || event.timestamp().isAfter(Instant.now().plusSeconds(MAX_FUTURE_SKEW_SECONDS))) {
            return ValidationOutcome.invalid(RejectionReason.TIMESTAMP_OUT_OF_RANGE,
                    "timestamp out of acceptable range: " + event.timestamp());
        }
        return ValidationOutcome.ok();
    }

    public static boolean isValidAddress(String address) {
        return address != null && ADDRESS_PATTERN.matcher(address).matches();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
