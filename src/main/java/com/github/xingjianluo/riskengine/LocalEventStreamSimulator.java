package com.github.xingjianluo.riskengine;

import com.github.xingjianluo.riskengine.alert.AlertService;
import com.github.xingjianluo.riskengine.ingest.EventIngestionService;
import com.github.xingjianluo.riskengine.model.IngestResult;
import com.github.xingjianluo.riskengine.model.TransactionEvent;
import com.github.xingjianluo.riskengine.score.RiskScoreResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地模拟事件流驱动器：应用启动时生成一批确定性事件
 * （含重复投递与乱序到达），并发摄入后打印评分与告警结论，
 * 用于本地验证"重复/乱序/并发下结论一致"。
 *
 * <p>通过 {@code risk.simulator.enabled=true} 开启。</p>
 */
@Component
@ConditionalOnProperty(name = "risk.simulator.enabled", havingValue = "true")
public class LocalEventStreamSimulator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalEventStreamSimulator.class);

    private final EventIngestionService ingestionService;
    private final AlertService alertService;

    public LocalEventStreamSimulator(EventIngestionService ingestionService, AlertService alertService) {
        this.ingestionService = ingestionService;
        this.alertService = alertService;
    }

    @Override
    public void run(String... args) throws Exception {
        List<TransactionEvent> events = buildDeterministicEventBatch();

        // 打乱顺序模拟乱序到达（固定种子，可复现），并追加重复投递。
        List<TransactionEvent> shuffled = new ArrayList<>(events);
        Collections.shuffle(shuffled, new java.util.Random(42));
        shuffled.addAll(events.subList(0, 3)); // 重复投递前 3 条

        log.info("simulator ingesting {} events ({} unique, {} duplicates)",
                shuffled.size(), events.size(), shuffled.size() - events.size());

        List<IngestResult> results = shuffled.parallelStream()
                .map(ingestionService::ingest)
                .toList();
        long accepted = results.stream().filter(IngestResult::accepted).count();
        long rejected = results.size() - accepted;
        log.info("simulator ingest done: accepted={} rejected={}", accepted, rejected);

        List<RiskScoreResult> alerts = alertService.alertsAboveThreshold();
        log.info("simulator alerts ({}):", alerts.size());
        alerts.forEach(a -> log.info("  {} score={} evidence={}", a.address(), a.score(), a.evidence()));
    }

    static List<TransactionEvent> buildDeterministicEventBatch() {
        String whale = addr("aa");
        List<TransactionEvent> events = new ArrayList<>();
        Instant base = Instant.parse("2026-09-01T00:00:00Z");

        // whale 向 6 个不同对手方大额转出 -> 命中 FAN_OUT + 多笔 LARGE_AMOUNT
        for (int i = 1; i <= 6; i++) {
            events.add(new TransactionEvent(
                    "evt-whale-out-" + i,
                    "0xtxwhale" + i,
                    whale,
                    addr(String.format("%02d", i)),
                    new BigDecimal("50000"),
                    "ETH",
                    base.plusSeconds(i * 60L)));
        }
        // 普通小额交易，不应触发告警
        for (int i = 1; i <= 3; i++) {
            events.add(new TransactionEvent(
                    "evt-small-" + i,
                    "0xtxsmall" + i,
                    addr("bb"),
                    addr("cc"),
                    new BigDecimal("10"),
                    "USDT",
                    base.plusSeconds(3600 + i * 60L)));
        }
        return List.copyOf(events);
    }

    private static String addr(String hexByte) {
        return "0x" + hexByte.repeat(20);
    }
}
