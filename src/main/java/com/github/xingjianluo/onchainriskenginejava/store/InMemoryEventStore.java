package com.github.xingjianluo.onchainriskenginejava.store;

import com.github.xingjianluo.onchainriskenginejava.model.IngestionResult;
import com.github.xingjianluo.onchainriskenginejava.model.RejectReason;
import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存事件存储（本地引擎使用）。
 *
 * <p><b>幂等与并发语义</b>：所有写入在单把 {@link ReentrantLock} 下完成
 * “eventId 判重 -> txHash 判重 -> 落库”这一原子动作，因此：</p>
 * <ul>
 *   <li>同一 eventId 的重复投递（哪怕来自不同线程）只有第一次被接受，后续得到
 *       {@link RejectReason#DUPLICATE_EVENT_ID}，不会重复计价；</li>
 *   <li>不同 eventId 但同一 txHash 的业务重复得到
 *       {@link RejectReason#DUPLICATE_TX_HASH}，同样可区分、不静默丢弃；</li>
 *   <li>读取返回不可变拷贝快照，写入不影响进行中的读取。</li>
 * </ul>
 */
@Component
public class InMemoryEventStore implements EventStore {

    private final ReentrantLock lock = new ReentrantLock();
    private final List<TransactionEvent> accepted = new ArrayList<>();
    private final Set<String> eventIds = new HashSet<>();
    private final Set<String> txHashes = new HashSet<>();
    private int rejectedCount = 0;

    @Override
    public IngestionResult ingest(TransactionEvent event) {
        lock.lock();
        try {
            if (!eventIds.add(event.eventId())) {
                rejectedCount++;
                return IngestionResult.rejected(RejectReason.DUPLICATE_EVENT_ID,
                        "eventId 重复投递: " + event.eventId(), event);
            }
            if (!txHashes.add(event.txHash())) {
                rejectedCount++;
                return IngestionResult.rejected(RejectReason.DUPLICATE_TX_HASH,
                        "txHash 与已接受交易重复: " + event.txHash(), event);
            }
            accepted.add(event);
            return IngestionResult.accepted(event);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<TransactionEvent> snapshotEvents() {
        lock.lock();
        try {
            return List.copyOf(accepted);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int acceptedCount() {
        lock.lock();
        try {
            return accepted.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int rejectedCount() {
        lock.lock();
        try {
            return rejectedCount;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void reset() {
        lock.lock();
        try {
            accepted.clear();
            eventIds.clear();
            txHashes.clear();
            rejectedCount = 0;
        } finally {
            lock.unlock();
        }
    }
}
