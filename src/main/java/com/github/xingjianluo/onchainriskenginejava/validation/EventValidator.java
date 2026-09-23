package com.github.xingjianluo.onchainriskenginejava.validation;

import com.github.xingjianluo.onchainriskenginejava.model.IngestionResult;
import com.github.xingjianluo.onchainriskenginejava.model.RejectReason;
import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 交易事件合法性校验：非法地址、缺失字段、越界数据在此被拦截并给出明确、可区分的原因，
 * 不会进入后续计价统计。
 *
 * <p>校验顺序固定，保证同一输入永远得到同一失败原因：
 * 缺失字段 -> 地址格式 -> 自发自收 -> 金额范围 -> 时间戳范围。</p>
 */
@Component
public class EventValidator {

    /** 金额硬上限，防止异常大数据污染统计。 */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");

    /** 允许的最大时间偏差（毫秒）：超过当前时间 1 天视为非法未来时间。 */
    public static final long MAX_FUTURE_MILLIS = 24L * 60 * 60 * 1000;

    private static final String ADDRESS_REGEX = "0x[0-9a-fA-F]{40}";

    /**
     * @return 合法时返回 accepted；否则返回带 {@link RejectReason} 的拒绝结果
     */
    public IngestionResult validate(TransactionEvent event) {
        if (event == null) {
            return IngestionResult.rejected(RejectReason.MISSING_FIELD,
                    "事件本体为空", null);
        }

        // 1) 缺失字段
        String missing = firstMissingField(event);
        if (missing != null) {
            return IngestionResult.rejected(RejectReason.MISSING_FIELD,
                    "缺失必填字段: " + missing, event);
        }

        // 2) 地址格式（地址在进入校验前已被规范化为小写）
        if (!event.from().matches(ADDRESS_REGEX)) {
            return IngestionResult.rejected(RejectReason.INVALID_ADDRESS,
                    "付款地址格式非法: " + event.from(), event);
        }
        if (!event.to().matches(ADDRESS_REGEX)) {
            return IngestionResult.rejected(RejectReason.INVALID_ADDRESS,
                    "收款地址格式非法: " + event.to(), event);
        }

        // 3) 自发自收
        if (event.from().equals(event.to())) {
            return IngestionResult.rejected(RejectReason.SELF_TRANSACTION,
                    "付款地址与收款地址相同: " + event.from(), event);
        }

        // 4) 金额范围：必须为正数且不超过上限
        if (event.amount().signum() <= 0 || event.amount().compareTo(MAX_AMOUNT) > 0) {
            return IngestionResult.rejected(RejectReason.AMOUNT_OUT_OF_RANGE,
                    "金额超出允许范围 (0, " + MAX_AMOUNT.toPlainString() + "]: " + event.amount().toPlainString(),
                    event);
        }

        // 5) 时间戳范围：必须为正且不能是过远的未来时间
        long now = System.currentTimeMillis();
        if (event.timestamp() <= 0L || event.timestamp() > now + MAX_FUTURE_MILLIS) {
            return IngestionResult.rejected(RejectReason.TIMESTAMP_OUT_OF_RANGE,
                    "时间戳超出允许范围 (0, now+" + MAX_FUTURE_MILLIS + "ms]: " + event.timestamp(), event);
        }

        return IngestionResult.accepted(event);
    }

    private String firstMissingField(TransactionEvent e) {
        if (isBlank(e.eventId())) {
            return "eventId";
        }
        if (isBlank(e.txHash())) {
            return "txHash";
        }
        if (isBlank(e.from())) {
            return "from";
        }
        if (isBlank(e.to())) {
            return "to";
        }
        if (e.amount() == null) {
            return "amount";
        }
        if (isBlank(e.asset())) {
            return "asset";
        }
        if (e.timestamp() == 0L) {
            // Web 层在 timestamp 缺失时会绑定为 0
            return "timestamp";
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
