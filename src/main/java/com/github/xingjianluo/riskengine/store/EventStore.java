package com.github.xingjianluo.riskengine.store;

import com.github.xingjianluo.riskengine.model.TransactionEvent;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的事件存储。
 *
 * <p>幂等语义（两级去重）：</p>
 * <ul>
 *   <li>投递级：同一 eventId 无论被多少线程、投递多少次，最多计价一次；</li>
 *   <li>交易级：同一 txHash（业务上的"同一笔链上交易"）即使换了 eventId、
 *       地址大小写不同、几十线程同时灌入，也只允许一条事件落库计价。</li>
 * </ul>
 *
 * <p>两个唯一键的"检查 + 写入"在同一把锁内原子完成，因此并发灌入同一笔交易的
 * 多个副本时，有且仅有一个线程拿到 {@link StoreResult#INSERTED}，其余线程
 * 能明确区分是投递编号撞车还是交易哈希撞车。</p>
 *
 * <p>快照语义：{@link #snapshot()} 返回当前已接受事件的不可变副本，
 * 与并发写入互不干扰；快照按 (timestamp, eventId) 确定性排序，
 * 使上层评分在任意摄入顺序下看到一致的输入序列。</p>
 */
@Repository
public class EventStore {

    /** 写入结果：让调用方能区分"投递编号重复"与"交易本身重复"。 */
    public enum StoreResult {
        INSERTED,
        DUPLICATE_EVENT_ID,
        DUPLICATE_TX_HASH
    }

    private final Map<String, TransactionEvent> eventsByEventId = new ConcurrentHashMap<>();
    private final Set<String> acceptedTxHashes = ConcurrentHashMap.newKeySet();

    /** 双键判重的原子临界区。 */
    private final Object dedupLock = new Object();

    /**
     * 幂等写入：eventId 与 txHash 均为首次见到才落库。
     * 先到记录不会被后到覆盖（乱序到达也不会让旧事件覆盖新事件）。
     */
    public StoreResult addIfAbsent(TransactionEvent event) {
        // 无锁快速路径：绝大多数重复投递在这里直接返回。
        if (eventsByEventId.containsKey(event.eventId())) {
            return StoreResult.DUPLICATE_EVENT_ID;
        }
        if (acceptedTxHashes.contains(event.txHash())) {
            return StoreResult.DUPLICATE_TX_HASH;
        }
        synchronized (dedupLock) {
            if (eventsByEventId.containsKey(event.eventId())) {
                return StoreResult.DUPLICATE_EVENT_ID;
            }
            // 先占 txHash：占住即表示这笔交易已有归属，其他 eventId 一律拒绝。
            if (!acceptedTxHashes.add(event.txHash())) {
                return StoreResult.DUPLICATE_TX_HASH;
            }
            eventsByEventId.put(event.eventId(), event);
            return StoreResult.INSERTED;
        }
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
