package com.github.xingjianluo.riskengine.score;

import com.github.xingjianluo.riskengine.ingest.EventValidator;
import com.github.xingjianluo.riskengine.model.TransactionEvent;
import com.github.xingjianluo.riskengine.store.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 风险评分服务。
 *
 * <p>确定性保证：评分只依赖 {@link EventStore#snapshot()} 的确定性排序快照，
 * 统计口径为"事件集合 + 交易对手集合"，与事件摄入顺序、重复投递无关；
 * 同一批已接受数据在任意处理顺序下得分一致。</p>
 *
 * <p>可解释性：每一条加分都在 evidence 中留下依据链
 * （规则版本、命中规则、事件 ID、交易哈希、金额、交易对手）。</p>
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

    public RiskScoreResult score(String address) {
        return score(address, null);
    }

    /**
     * 按指定规则版本评分；version 为 null 时使用当前生效版本。
     * 历史版本取自系统内部留存的版本历史，无需调用方自己保存旧规则。
     *
     * @throws RuleVersionNotFoundException 指定的版本从未发布过
     */
    public RiskScoreResult score(String address, Integer version) {
        if (!EventValidator.isValidAddress(address)) {
            throw new IllegalArgumentException("invalid address: " + address);
        }
        RiskRuleSet rules = resolveRules(version);
        List<TransactionEvent> snapshot = eventStore.snapshot();
        RiskScoreResult result = scoreOnSnapshot(address.toLowerCase(), snapshot, rules);
        log.info("score address={} rulesVersion={} score={} evidence={}",
                result.address(), result.ruleSetVersion(), result.score(), result.evidence());
        return result;
    }

    /** 解析本次查询要使用的规则集：null -> 当前版本；否则必须已发布过。 */
    public RiskRuleSet resolveRules(Integer version) {
        if (version == null) {
            return ruleSetProvider.current();
        }
        RiskRuleSet rules = ruleSetProvider.version(version);
        if (rules == null) {
            throw new RuleVersionNotFoundException(version);
        }
        return rules;
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
        evidence.add("ruleset=v" + rules.version());

        TreeSet<String> outCounterparties = new TreeSet<>();
        TreeSet<String> inCounterparties = new TreeSet<>();
        List<TransactionEvent> involved = new ArrayList<>();
        List<TransactionEvent> largeAmountEvents = new ArrayList<>();
        List<TransactionEvent> outgoingEvents = new ArrayList<>();
        List<TransactionEvent> incomingEvents = new ArrayList<>();

        for (TransactionEvent e : snapshot) {
            boolean outgoing = e.fromAddress().equals(address);
            boolean incoming = e.toAddress().equals(address);
            if (!outgoing && !incoming) {
                continue;
            }
            involved.add(e);
            if (outgoing) {
                outCounterparties.add(e.toAddress());
                outgoingEvents.add(e);
            }
            if (incoming) {
                inCounterparties.add(e.fromAddress());
                incomingEvents.add(e);
            }
            if (e.amount().compareTo(rules.largeAmountThreshold()) >= 0) {
                score += rules.largeAmountWeight();
                largeAmountEvents.add(e);
                evidence.add(String.format(
                        "LARGE_AMOUNT(+%d): event=%s tx=%s %s %s %s->%s >= threshold %s",
                        rules.largeAmountWeight(), e.eventId(), e.txHash(),
                        e.amount().toPlainString(), e.asset(),
                        e.fromAddress(), e.toAddress(),
                        rules.largeAmountThreshold().toPlainString()));
            }
        }

        List<RuleHit> ruleHits = new ArrayList<>();
        if (!largeAmountEvents.isEmpty()) {
            ruleHits.add(buildHit("LARGE_AMOUNT", address, largeAmountEvents));
        }

        if (outCounterparties.size() >= rules.fanOutThreshold()) {
            score += rules.fanOutWeight();
            evidence.add(String.format("FAN_OUT(+%d): %d distinct out-counterparties >= %d %s",
                    rules.fanOutWeight(), outCounterparties.size(),
                    rules.fanOutThreshold(), outCounterparties));
            ruleHits.add(buildHit("FAN_OUT", address, outgoingEvents));
        }
        if (inCounterparties.size() >= rules.fanInThreshold()) {
            score += rules.fanInWeight();
            evidence.add(String.format("FAN_IN(+%d): %d distinct in-counterparties >= %d %s",
                    rules.fanInWeight(), inCounterparties.size(),
                    rules.fanInThreshold(), inCounterparties));
            ruleHits.add(buildHit("FAN_IN", address, incomingEvents));
        }

        int finalScore = Math.min(score, MAX_SCORE);
        evidence.add("TOTAL=" + finalScore + (score > MAX_SCORE ? " (capped from " + score + ")" : ""));
        return new RiskScoreResult(address, finalScore, rules.version(),
                List.copyOf(evidence), List.copyOf(ruleHits),
                EventSetFingerprint.of(involved));
    }

    /**
     * 由一组关联交易构造一条结构化命中记录。输入事件来自确定性排序的快照，
     * 集合字段再统一排序，因此输出与事件到达顺序、重复投递次数无关。
     */
    private static RuleHit buildHit(String rule, String address, List<TransactionEvent> related) {
        TreeSet<String> counterparties = new TreeSet<>();
        TreeSet<String> eventIds = new TreeSet<>();
        TreeSet<String> txHashes = new TreeSet<>();
        java.math.BigDecimal totalAmount = java.math.BigDecimal.ZERO;
        boolean anyOutgoing = false;
        boolean anyIncoming = false;
        for (TransactionEvent e : related) {
            if (e.fromAddress().equals(address)) {
                anyOutgoing = true;
                counterparties.add(e.toAddress());
            }
            if (e.toAddress().equals(address)) {
                anyIncoming = true;
                counterparties.add(e.fromAddress());
            }
            eventIds.add(e.eventId());
            txHashes.add(e.txHash());
            totalAmount = totalAmount.add(e.amount());
        }
        String direction = anyOutgoing && anyIncoming ? RuleHit.DIRECTION_BOTH
                : anyOutgoing ? RuleHit.DIRECTION_OUTGOING : RuleHit.DIRECTION_INCOMING;
        return new RuleHit(rule, direction,
                List.copyOf(counterparties), totalAmount.stripTrailingZeros(),
                List.copyOf(eventIds), List.copyOf(txHashes));
    }
}
