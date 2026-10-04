package com.github.xingjianluo.riskengine.store;

import com.github.xingjianluo.riskengine.model.TransactionEvent;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的事件存储。
 *
 * <p>幂等语义：业务上的"同一笔交易"只计价一次。判定口径为
 * （eventId, 归一化后的 txHash）两级去重——eventId 相同视为同一次投递，
 * txHash 相同（忽略大小写）视为同一笔链上交易，二者任一命中都拒绝。
 * {@link #addIfAbsent} 在同一把锁内完成两级检查与写入，因此几十个线程
 * 同时灌入重复数据时，同一笔交易也只会被接受一次。</p>
 *
 * <p>快照语义：{@link #snapshot()} 返回当前已接受事件的不可变副本，
 * 与并发写入互不干扰；快照按 (timestamp, eventId) 确定性排序，
 * 使上层评分在任意摄入顺序下看到一致的输入序列。</p>
 */
@Repository
public class EventStore {

    /** 两级去重的写入结果，供上层给出可区分的拒绝原因。 */
    public enum AddOutcome {
        ACCEPTED,
        DUPLICATE_EVENT_ID,
        DUPLICATE_TX_HASH
    }

    private final Map<String, TransactionEvent> eventsByEventId = new ConcurrentHashMap<>();
    /** 归一化（小写）txHash -> 首次接受该交易的 eventId。 */
    private final Map<String, String> eventIdByTxHash = new ConcurrentHashMap<>();
    /** 保护"检查 eventId + 检查 txHash + 写入"这一组合操作的原子性。 */
    private final Object dedupLock = new Object();

    /**
     * 幂等写入：eventId 与 txHash 都首次出现才写入；
     * 否则返回可区分的重复类型，已有记录不会被覆盖。
     */
    public AddOutcome addIfAbsent(TransactionEvent event) {
        String txKey = normalizeTxHash(event.txHash());
        synchronized (dedupLock) {
            if (eventsByEventId.containsKey(event.eventId())) {
                return AddOutcome.DUPLICATE_EVENT_ID;
            }
            if (eventIdByTxHash.containsKey(txKey)) {
                return AddOutcome.DUPLICATE_TX_HASH;
            }
            eventsByEventId.put(event.eventId(), event);
            eventIdByTxHash.put(txKey, event.eventId());
            return AddOutcome.ACCEPTED;
        }
    }

    /** 查询某笔链上交易首次被接受时的 eventId；未接受过返回 null。 */
    public String findEventIdByTxHash(String txHash) {
        return txHash == null ? null : eventIdByTxHash.get(normalizeTxHash(txHash));
    }

    private static String normalizeTxHash(String txHash) {
        return txHash.toLowerCase(java.util.Locale.ROOT);
    }

    /** 返回确定性排序的不可变事件快照。 */
    public List<TransactionEvent> snapshot() {
        List<TransactionEvent> copy = new ArrayList<>(eventsByEventId.values());
        copy.sort((a, b) -> {
            int byTime = a.timestamp().compareTo(b.timestamp());
            return byTime != 0 ? byTime : a.eventId().compareTo(b.eventId());
        });
        return List.copyOf(copy);
    }

    public int size() {
        return eventsByEventId.size();
    }
}
