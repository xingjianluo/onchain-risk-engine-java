package com.github.xingjianluo.riskengine.score;

import com.github.xingjianluo.riskengine.ingest.EventValidator;
import com.github.xingjianluo.riskengine.model.TransactionEvent;
import com.github.xingjianluo.riskengine.store.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 风险评分服务。
 *
 * <p>确定性保证：评分只依赖 {@link EventStore#snapshot()} 的确定性排序快照，
 * 统计口径为"事件集合 + 交易对手集合"，与事件摄入顺序、重复投递无关；
 * 同一批已接受数据在任意处理顺序下，得分、文字依据链、结构化命中记录
 * 与事件集合指纹全部一致。</p>
 *
 * <p>可解释性：每一条加分既在 evidence 中留下人读依据链，也在 ruleHits 中
 * 留下结构化记录（规则、方向、对手方、金额、关联事件与交易哈希），
 * 并附事件集合指纹供自动对账。</p>
 */
@Service
public class RiskScoringService {

    private static final Logger log = LoggerFactory.getLogger(RiskScoringService.class);
    private static final int MAX_SCORE = 100;

    private final EventStore eventStore;
    private final RuleSetProvider ruleSetProvider;

    public RiskScoringService(EventStore eventStore, RuleSetProvider ruleSetProvider) {
        this.eventStore = eventStore;
        this.ruleSetProvider = ruleSetProvider;
    }

    /** 按当前生效规则集评分。 */
    public RiskScoreResult score(String address) {
        RiskRuleSet rules = ruleSetProvider.current();
        return scoreWithRules(address, rules);
    }

    /**
     * 按指定历史版本规则集复算：只凭版本号即可重现当时的评分与依据，
     * 无论中间发布过多少轮更新。
     */
    public RiskScoreResult score(String address, int ruleSetVersion) {
        RiskRuleSet rules = ruleSetProvider.version(ruleSetVersion);
        RiskScoreResult result = scoreWithRules(address, rules);
        log.info("replay score address={} requestedVersion={} score={}",
                result.address(), ruleSetVersion, result.score());
        return result;
    }

    private RiskScoreResult scoreWithRules(String address, RiskRuleSet rules) {
        if (!EventValidator.isValidAddress(address)) {
            throw new IllegalArgumentException("invalid address: " + address);
        }
        RiskScoreResult result = scoreOnSnapshot(address.toLowerCase(), eventStore.snapshot(), rules);
        log.info("score address={} rulesVersion={} score={} fingerprint={} evidence={}",
                result.address(), result.ruleSetVersion(), result.score(),
                result.eventSetFingerprint(), result.evidence());
        return result;
    }

    /** 对快照中所有出现过的钱包评分（告警查询使用），地址按字典序处理保证确定性。 */
    public List<RiskScoreResult> scoreAllOnSnapshot(List<TransactionEvent> snapshot, RiskRuleSet rules) {
        TreeSet<String> addresses = new TreeSet<>();
        for (TransactionEvent e : snapshot) {
            addresses.add(e.fromAddress());
            addresses.add(e.toAddress());
        }
        List<RiskScoreResult> results = new ArrayList<>();
        for (String address : addresses) {
            results.add(scoreOnSnapshot(address, snapshot, rules));
        }
        return results;
    }

    /** 在给定快照与规则集上评分——纯函数，可复现历史结论。 */
    public RiskScoreResult scoreOnSnapshot(String address, List<TransactionEvent> snapshot, RiskRuleSet rules) {
        int score = 0;
        List<String> evidence = new ArrayList<>();
        List<RuleHit> ruleHits = new ArrayList<>();
        evidence.add("ruleset=v" + rules.version());

        TreeSet<String> outCounterparties = new TreeSet<>();
        TreeSet<String> inCounterparties = new TreeSet<>();
        // 聚合命中（FAN_OUT/FAN_IN）的结构化字段：确定性来自快照的固定排序。
        List<String> outEventIds = new ArrayList<>();
        List<String> outTxHashes = new ArrayList<>();
        List<String> inEventIds = new ArrayList<>();
        List<String> inTxHashes = new ArrayList<>();
        BigDecimal outTotal = BigDecimal.ZERO;
        BigDecimal inTotal = BigDecimal.ZERO;

        for (TransactionEvent e : snapshot) {
            boolean outgoing = e.fromAddress().equals(address);
            boolean incoming = e.toAddress().equals(address);
            if (!outgoing && !incoming) {
                continue;
            }
            if (outgoing) {
                outCounterparties.add(e.toAddress());
                outEventIds.add(e.eventId());
                outTxHashes.add(e.txHash());
                outTotal = outTotal.add(e.amount());
            }
            if (incoming) {
                inCounterparties.add(e.fromAddress());
                inEventIds.add(e.eventId());
                inTxHashes.add(e.txHash());
                inTotal = inTotal.add(e.amount());
            }
            if (e.amount().compareTo(rules.largeAmountThreshold()) >= 0) {
                score += rules.largeAmountWeight();
                evidence.add(String.format(
                        "LARGE_AMOUNT(+%d): event=%s tx=%s %s %s %s->%s >= threshold %s",
                        rules.largeAmountWeight(), e.eventId(), e.txHash(),
                        e.amount().toPlainString(), e.asset(),
                        e.fromAddress(), e.toAddress(),
                        rules.largeAmountThreshold().toPlainString()));
                ruleHits.add(new RuleHit(
                        "LARGE_AMOUNT",
                        outgoing ? "OUTGOING" : "INCOMING",
                        rules.largeAmountWeight(),
                        e.amount(),
                        e.asset(),
                        List.of(outgoing ? e.toAddress() : e.fromAddress()),
                        List.of(e.eventId()),
                        List.of(e.txHash())));
            }
        }

        if (outCounterparties.size() >= rules.fanOutThreshold()) {
            score += rules.fanOutWeight();
            evidence.add(String.format("FAN_OUT(+%d): %d distinct out-counterparties >= %d %s",
                    rules.fanOutWeight(), outCounterparties.size(),
                    rules.fanOutThreshold(), outCounterparties));
            ruleHits.add(new RuleHit(
                    "FAN_OUT", "OUTGOING", rules.fanOutWeight(),
                    outTotal, null,
                    List.copyOf(outCounterparties),
                    List.copyOf(outEventIds), List.copyOf(outTxHashes)));
        }
        if (inCounterparties.size() >= rules.fanInThreshold()) {
            score += rules.fanInWeight();
            evidence.add(String.format("FAN_IN(+%d): %d distinct in-counterparties >= %d %s",
                    rules.fanInWeight(), inCounterparties.size(),
                    rules.fanInThreshold(), inCounterparties));
            ruleHits.add(new RuleHit(
                    "FAN_IN", "INCOMING", rules.fanInWeight(),
                    inTotal, null,
                    List.copyOf(inCounterparties),
                    List.copyOf(inEventIds), List.copyOf(inTxHashes)));
        }

        int finalScore = Math.min(score, MAX_SCORE);
        evidence.add("TOTAL=" + finalScore + (score > MAX_SCORE ? " (capped from " + score + ")" : ""));
        String fingerprint = EventSetFingerprint.of(address, snapshot);
        return new RiskScoreResult(address, finalScore, rules.version(),
                List.copyOf(evidence), List.copyOf(ruleHits), fingerprint);
    }
}
