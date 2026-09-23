package com.github.xingjianluo.onchainriskenginejava.service;

import com.github.xingjianluo.onchainriskenginejava.engine.ScoringEngine;
import com.github.xingjianluo.onchainriskenginejava.engine.WalletGraph;
import com.github.xingjianluo.onchainriskenginejava.model.IngestionResult;
import com.github.xingjianluo.onchainriskenginejava.model.RejectReason;
import com.github.xingjianluo.onchainriskenginejava.model.RiskAlert;
import com.github.xingjianluo.onchainriskenginejava.model.RiskScore;
import com.github.xingjianluo.onchainriskenginejava.model.SnapshotInfo;
import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;
import com.github.xingjianluo.onchainriskenginejava.rules.RiskRuleSet;
import com.github.xingjianluo.onchainriskenginejava.rules.builtin.BuiltInRules;
import com.github.xingjianluo.onchainriskenginejava.rules.RuleRegistry;
import com.github.xingjianluo.onchainriskenginejava.store.EventStore;
import com.github.xingjianluo.onchainriskenginejava.validation.EventValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 默认风控引擎应用服务。
 *
 * <h3>一致性快照语义</h3>
 * <p>每次评分 / 告警查询都在一次逻辑动作中：①取出事件不可变快照；②取出当前不可变规则集。
 * 规则发布是原子引用交换，事件快照是不可变拷贝，因此一次查询自始至终基于同一份
 * (事件集, 规则集) 视图，规则更新与查询并发时绝不会读到“新规则 + 半截数据”。
 * 快照附带 {@link SnapshotInfo}（规则版本、事件数、事件集 SHA-256），供审计与复现。</p>
 */
@Service
public class DefaultRiskEngineService implements RiskEngineService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRiskEngineService.class);
    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-f]{40}");

    private final EventValidator validator;
    private final EventStore store;
    private final RuleRegistry ruleRegistry;
    private final ScoringEngine scoringEngine;

    public DefaultRiskEngineService(EventValidator validator,
                                    EventStore store,
                                    RuleRegistry ruleRegistry,
                                    ScoringEngine scoringEngine) {
        this.validator = validator;
        this.store = store;
        this.ruleRegistry = ruleRegistry;
        this.scoringEngine = scoringEngine;
    }

    @Override
    public IngestionResult ingest(TransactionEvent raw) {
        TransactionEvent event = normalize(raw);
        IngestionResult validation = validator.validate(event);
        if (!validation.accepted()) {
            log.info("摄入拒绝 | eventId={} txHash={} reason={} detail={}",
                    event.eventId(), event.txHash(), validation.reason(), validation.message());
            return validation;
        }
        IngestionResult stored = store.ingest(event);
        if (!stored.accepted()) {
            log.info("摄入拒绝 | eventId={} txHash={} reason={} detail={}",
                    event.eventId(), event.txHash(), stored.reason(), stored.message());
        } else {
            log.info("摄入接受 | eventId={} txHash={} ts={} {} -> {} amount={} {}",
                    event.eventId(), event.txHash(), event.timestamp(), event.from(), event.to(),
                    event.amount().toPlainString(), event.asset());
        }
        return stored;
    }

    @Override
    public List<IngestionResult> ingestBatch(List<TransactionEvent> events) {
        if (events == null) {
            return List.of();
        }
        List<IngestionResult> results = new ArrayList<>(events.size());
        for (TransactionEvent e : events) {
            results.add(ingest(e));
        }
        return List.copyOf(results);
    }

    @Override
    public RiskScore score(String address) {
        return scoreWithVersion(address, ruleRegistry.current().version());
    }

    @Override
    public RiskScore scoreWithVersion(String address, String ruleSetVersion) {
        String normalizedAddress = normalizeAddress(address);
        if (normalizedAddress == null || !ADDRESS.matcher(normalizedAddress).matches()) {
            throw new IllegalArgumentException("查询地址非法: " + address);
        }
        RiskRuleSet ruleSet = ruleRegistry.byVersion(ruleSetVersion)
                .orElseThrow(() -> new IllegalArgumentException("规则集版本不存在: " + ruleSetVersion));

        // 一次查询内，事件快照与规则集都在本方法内固定下来，保证快照一致性
        List<TransactionEvent> events = store.snapshotEvents();
        WalletGraph graph = WalletGraph.from(events);
        SnapshotInfo snapshot = new SnapshotInfo(ruleSet.version(), events.size(), hashOf(events));

        RiskScore riskScore = scoringEngine.score(normalizedAddress, graph, ruleSet, snapshot);
        log.info("评分 | address={} version={} score={}/{} rawPoints={} rulesHit={}",
                normalizedAddress, ruleSetVersion, riskScore.score(), ScoringEngine.MAX_SCORE,
                riskScore.rawPoints(),
                riskScore.evidences().stream().map(e -> e.ruleCode() + "(" + e.points() + ")").toList());
        riskScore.evidences().forEach(ev -> log.info("  依据 {} [{}分] {} facts={}",
                ev.ruleCode(), ev.points(), ev.rationale(), ev.facts()));
        return riskScore;
    }

    @Override
    public List<RiskAlert> alerts(int threshold) {
        if (threshold < 0 || threshold > ScoringEngine.MAX_SCORE) {
            throw new IllegalArgumentException("阈值必须在 [0,100]: " + threshold);
        }
        // 整个告警查询复用同一份 (事件快照, 规则集)：不会因并发规则更新或摄入造成结果撕裂
        List<TransactionEvent> events = store.snapshotEvents();
        RiskRuleSet ruleSet = ruleRegistry.current();
        WalletGraph graph = WalletGraph.from(events);
        SnapshotInfo snapshot = new SnapshotInfo(ruleSet.version(), events.size(), hashOf(events));

        List<RiskAlert> alerts = new ArrayList<>();
        for (String address : graph.allAddresses()) {
            RiskScore riskScore = scoringEngine.score(address, graph, ruleSet, snapshot);
            if (riskScore.score() >= threshold) {
                List<String> codes = riskScore.evidences().stream().map(e -> e.ruleCode()).toList();
                alerts.add(new RiskAlert(address, riskScore.score(), threshold, codes, snapshot));
            }
        }
        alerts.sort((a, b) -> {
            int byScore = Integer.compare(b.score(), a.score());
            return byScore != 0 ? byScore : a.address().compareTo(b.address());
        });
        log.info("告警查询 | threshold={} version={} alerts={}", threshold, ruleSet.version(), alerts.size());
        alerts.forEach(a -> log.info("  告警 address={} score={} rules={}",
                a.address(), a.score(), a.triggeredRuleCodes()));
        return List.copyOf(alerts);
    }

    @Override
    public String publishBuiltIn(String name) {
        if ("v1".equalsIgnoreCase(name)) {
            return publishRules(BuiltInRules.v1(BuiltInRules.DEFAULT_RISKY_ADDRESSES));
        }
        if ("v2".equalsIgnoreCase(name)) {
            return publishRules(BuiltInRules.v2(BuiltInRules.DEFAULT_RISKY_ADDRESSES));
        }
        throw new IllegalArgumentException("未知内置规则集名称: " + name + "（支持 v1 / v2）");
    }

    @Override
    public String publishRules(RiskRuleSet ruleSet) {
        ruleRegistry.publish(ruleSet);
        log.info("规则集发布 | activeVersion={} rules={}", ruleSet.version(),
                ruleSet.rules().stream().map(r -> r.code() + "@" + r.version()).toList());
        return ruleSet.version();
    }

    @Override
    public String activateVersion(String version) {
        ruleRegistry.activateExisting(version);
        log.info("规则集切换 | activeVersion={}", version);
        return version;
    }

    @Override
    public void reset() {
        store.reset();
        log.info("引擎事件数据已重置（规则集保留: {}）", ruleRegistry.current().version());
    }

    /**
     * 规范化事件：地址统一转小写（链上地址大小写不敏感），保证同一地址不会因大小写
     * 不同被拆成多个钱包。事件本身不做其他字段改写。
     */
    private static TransactionEvent normalize(TransactionEvent raw) {
        if (raw == null) {
            return null;
        }
        return new TransactionEvent(
                raw.eventId(),
                raw.txHash(),
                raw.timestamp(),
                normalizeAddress(raw.from()),
                normalizeAddress(raw.to()),
                raw.amount(),
                raw.asset()
        );
    }

    private static String normalizeAddress(String address) {
        return address == null ? null : address.trim().toLowerCase();
    }

    /**
     * 规范化事件集的 SHA-256：先按与 {@link WalletGraph} 相同的主键排序，再逐行拼接定长字段。
     * 任何重复（已在存储层排除）、乱序都不会改变哈希。
     */
    static String hashOf(List<TransactionEvent> events) {
        List<TransactionEvent> sorted = new ArrayList<>(events);
        sorted.sort(java.util.Comparator
                .comparingLong(TransactionEvent::timestamp)
                .thenComparing(TransactionEvent::txHash)
                .thenComparing(TransactionEvent::from)
                .thenComparing(TransactionEvent::to));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (TransactionEvent e : sorted) {
                String line = String.join("|",
                        str(e.eventId()), str(e.txHash()), Long.toString(e.timestamp()),
                        str(e.from()), str(e.to()),
                        e.amount() == null ? "" : e.amount().stripTrailingZeros().toPlainString(),
                        str(e.asset()));
                md.update(line.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
