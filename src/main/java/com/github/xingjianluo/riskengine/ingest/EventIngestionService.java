package com.github.xingjianluo.riskengine.ingest;

import com.github.xingjianluo.riskengine.model.IngestResult;
import com.github.xingjianluo.riskengine.model.RejectionReason;
import com.github.xingjianluo.riskengine.model.TransactionEvent;
import com.github.xingjianluo.riskengine.store.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 事件摄入服务：先做字段校验，再做幂等落库。
 *
 * <p>地址在校验后统一归一化为小写，保证同一地址大小写混写时
 * 仍然归入同一个钱包（资金流向统计与顺序无关）。</p>
 *
 * <p>交易哈希同样统一归一化为小写后再落库：同一笔链上交易无论上游用
 * 大写、小写还是混合写法投递，留存记录、评分依据链、结构化判定依据与
 * 事件集合指纹里出现的都是同一种（小写）写法，与到达顺序无关。</p>
 */
@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    private final EventValidator validator;
    private final EventStore eventStore;

    public EventIngestionService(EventValidator validator, EventStore eventStore) {
        this.validator = validator;
        this.eventStore = eventStore;
    }

    public IngestResult ingest(TransactionEvent event) {
        log.info("ingest input event: {}", event);
        if (event == null) {
            return reject(null, RejectionReason.MISSING_EVENT_ID, "event is null");
        }

        EventValidator.ValidationOutcome outcome = validator.validate(event);
        if (!outcome.valid()) {
            return reject(event.eventId(), outcome.reason(), outcome.detail());
        }

        TransactionEvent normalized = normalize(event);
        EventStore.AddOutcome addOutcome = eventStore.addIfAbsent(normalized);
        switch (addOutcome) {
            case DUPLICATE_EVENT_ID -> {
                return reject(normalized.eventId(), RejectionReason.DUPLICATE_EVENT,
                        "eventId already accepted: " + normalized.eventId());
            }
            case DUPLICATE_TX_HASH -> {
                String firstEventId = eventStore.findEventIdByTxHash(normalized.txHash());
                return reject(normalized.eventId(), RejectionReason.DUPLICATE_TRANSACTION,
                        "txHash already accounted by another event: txHash=" + normalized.txHash()
                                + " firstEventId=" + firstEventId);
            }
            default -> {
                // ACCEPTED，继续往下走
            }
        }

        log.info("ingest accepted eventId={} txHash={} {} -> {} {} {}",
                normalized.eventId(), normalized.txHash(),
                normalized.fromAddress(), normalized.toAddress(),
                normalized.amount(), normalized.asset());
        return IngestResult.ok();
    }

    private static IngestResult reject(String eventId, RejectionReason reason, String detail) {
        log.warn("ingest rejected eventId={} reason={} detail={}", eventId, reason, detail);
        return IngestResult.rejected(reason, detail);
    }

    private static TransactionEvent normalize(TransactionEvent e) {
        return new TransactionEvent(
                e.eventId(),
                e.txHash().toLowerCase(java.util.Locale.ROOT),
                e.fromAddress().toLowerCase(),
                e.toAddress().toLowerCase(),
                e.amount().stripTrailingZeros(),
                e.asset().trim().toUpperCase(),
                e.timestamp());
    }
}
