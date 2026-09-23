package com.github.xingjianluo.onchainriskenginejava.store;

import com.github.xingjianluo.onchainriskenginejava.model.IngestionResult;
import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;

import java.util.List;

/**
 * 事件存储：负责幂等摄入（eventId / txHash 判重）与一致性快照读取。
 * 并发写入在单把锁下完成“判重 + 落库”原子动作，保证重复计价不可能发生；
 * 读取返回不可变列表快照。
 */
public interface EventStore {

    /**
     * 原子地完成判重与写入。
     *
     * @return ACCEPTED，或 DUPLICATE_EVENT_ID / DUPLICATE_TX_HASH 拒绝结果
     */
    IngestionResult ingest(TransactionEvent event);

    /** 返回当前已接受事件的不可变快照。 */
    List<TransactionEvent> snapshotEvents();

    int acceptedCount();

    int rejectedCount();

    void reset();
}
