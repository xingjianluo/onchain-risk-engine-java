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
 * <p>幂等语义：以 eventId 为唯一键，{@link #addIfAbsent} 原子执行，
 * 无论并发写入、重复投递还是乱序到达，每个 eventId 最多被计价一次。</p>
 *
 * <p>快照语义：{@link #snapshot()} 返回当前已接受事件的不可变副本，
 * 与并发写入互不干扰；快照按 (timestamp, eventId) 确定性排序，
 * 使上层评分在任意摄入顺序下看到一致的输入序列。</p>
 */
@Repository
public class EventStore {

    private final Map<String, TransactionEvent> eventsByEventId = new ConcurrentHashMap<>();

    /**
     * 幂等写入：首次见到的 eventId 写入并返回 true；重复 eventId 返回 false，
     * 已有记录不会被覆盖（乱序到达也不会让旧事件覆盖新事件）。
     */
    public boolean addIfAbsent(TransactionEvent event) {
        return eventsByEventId.putIfAbsent(event.eventId(), event) == null;
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
