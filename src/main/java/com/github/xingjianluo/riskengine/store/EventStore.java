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
 * <p>规范留存（canonical retention）：同一笔交易被换投递编号重投时，
 * 拒绝响应不变（DUPLICATE_TRANSACTION），但留存记录会收敛为
 * <b>投递编号字典序最小</b>的那一条——后到的投递若编号更小，会替换掉
 * 当前留存的记录。因此无论各版本按什么顺序到达、以何种并发交错灌入，
 * 最终留存的记录只取决于"投递集合"本身，评分依据链、结构化判定依据与
 * 事件集合指纹在任意到达顺序下逐字段一致。</p>
 *
 * <p>曾被接受过的投递编号会被记住：即使其记录后来因规范留存被替换，
 * 同一编号再次投递仍报 {@code DUPLICATE_EVENT}（而非 DUPLICATE_TRANSACTION），
 * 调用方看到的拒因语义与替换前一致。</p>
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
    /** 归一化（小写）txHash -> 当前留存记录的 eventId。 */
    private final Map<String, String> eventIdByTxHash = new ConcurrentHashMap<>();
    /** 曾被接受过的全部 eventId（含因规范留存被替换下去的），用于区分两种拒因。 */
    private final java.util.Set<String> acceptedEventIds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 保护"检查 eventId + 检查 txHash + 写入/替换"这一组合操作的原子性。 */
    private final Object dedupLock = new Object();

    /**
     * 幂等写入：eventId 与 txHash 都首次出现才写入；
     * 否则返回可区分的重复类型。
     *
     * <p>命中 DUPLICATE_TX_HASH 时，若本次投递的 eventId 字典序小于当前留存记录，
     * 则用本条事件替换留存记录（规范留存），保证同一笔交易无论按什么顺序、
     * 多少次重投，最终留存的记录都相同。</p>
     */
    public AddOutcome addIfAbsent(TransactionEvent event) {
        String txKey = normalizeTxHash(event.txHash());
        synchronized (dedupLock) {
            if (acceptedEventIds.contains(event.eventId())) {
                return AddOutcome.DUPLICATE_EVENT_ID;
            }
            String retainedEventId = eventIdByTxHash.get(txKey);
            if (retainedEventId != null) {
                // 规范留存：收敛到投递编号字典序最小的那条记录
                if (event.eventId().compareTo(retainedEventId) < 0) {
                    eventsByEventId.remove(retainedEventId);
                    eventsByEventId.put(event.eventId(), event);
                    eventIdByTxHash.put(txKey, event.eventId());
                }
                return AddOutcome.DUPLICATE_TX_HASH;
            }
            eventsByEventId.put(event.eventId(), event);
            eventIdByTxHash.put(txKey, event.eventId());
            acceptedEventIds.add(event.eventId());
            return AddOutcome.ACCEPTED;
        }
    }

    /** 查询某笔链上交易当前留存记录的 eventId（投递编号字典序最小者）；未接受过返回 null。 */
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
