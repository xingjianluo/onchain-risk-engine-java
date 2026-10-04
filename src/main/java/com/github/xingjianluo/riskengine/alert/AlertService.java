package com.github.xingjianluo.riskengine.alert;

import com.github.xingjianluo.riskengine.model.TransactionEvent;
import com.github.xingjianluo.riskengine.score.RiskRuleSet;
import com.github.xingjianluo.riskengine.score.RiskScoreResult;
import com.github.xingjianluo.riskengine.score.RiskScoringService;
import com.github.xingjianluo.riskengine.store.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 阈值触发的风险告警查询。
 *
 * <p>快照语义：一次查询开始时先取一次规则集引用、再取一次事件快照，
 * 整个查询期间二者固定不变；并发的规则发布或事件写入只会影响下一次查询，
 * 本次查询内部结论自洽、可复现。</p>
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final EventStore eventStore;
    private final RiskScoringService scoringService;

    public AlertService(EventStore eventStore, RiskScoringService scoringService) {
        this.eventStore = eventStore;
        this.scoringService = scoringService;
    }

    public List<RiskScoreResult> alertsAboveThreshold() {
        return alertsAboveThreshold(null);
    }

    /**
     * 按指定规则版本查询告警；version 为 null 时使用当前生效版本。
     * 一次查询固定使用"一个完整规则集 + 一个事件快照"，不新旧混用。
     */
    public List<RiskScoreResult> alertsAboveThreshold(Integer version) {
        RiskRuleSet rules = scoringService.resolveRules(version);
        List<TransactionEvent> snapshot = eventStore.snapshot();

        List<RiskScoreResult> alerts = scoringService.scoreAllOnSnapshot(snapshot, rules).stream()
                .filter(r -> r.score() >= rules.alertScoreThreshold())
                .sorted(Comparator.comparingInt(RiskScoreResult::score).reversed()
                        .thenComparing(RiskScoreResult::address))
                .toList();

        log.info("alert query: rulesVersion={} threshold={} snapshotEvents={} alerts={}",
                rules.version(), rules.alertScoreThreshold(), snapshot.size(), alerts.size());
        return alerts;
    }
}
