package com.github.xingjianluo.onchainriskenginejava.engine;

import com.github.xingjianluo.onchainriskenginejava.model.Evidence;
import com.github.xingjianluo.onchainriskenginejava.model.RiskScore;
import com.github.xingjianluo.onchainriskenginejava.model.SnapshotInfo;
import com.github.xingjianluo.onchainriskenginejava.rules.RiskRule;
import com.github.xingjianluo.onchainriskenginejava.rules.RiskRuleSet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 评分引擎。
 *
 * <p><b>确定性</b>：评分是 (address, WalletGraph, RiskRuleSet) 的纯函数——
 * 规则逐条求值，命中依据按规则码排序，分值累加后在 [0,100] 封顶。
 * 同一批数据无论以何种顺序摄入、由哪个线程查询，得到完全一致的分数与依据链。</p>
 */
@Component
public class ScoringEngine {

    public static final int MAX_SCORE = 100;

    public RiskScore score(String address, WalletGraph graph, RiskRuleSet ruleSet, SnapshotInfo snapshot) {
        List<Evidence> hits = new ArrayList<>();
        int rawPoints = 0;
        for (RiskRule rule : ruleSet.rules()) {
            var hit = rule.evaluate(address, graph);
            if (hit.isPresent()) {
                Evidence evidence = hit.get();
                hits.add(evidence);
                rawPoints += evidence.points();
            }
        }
        hits.sort(Comparator.comparing(Evidence::ruleCode));
        int score = Math.max(0, Math.min(MAX_SCORE, rawPoints));
        return new RiskScore(address, rawPoints, score, List.copyOf(hits), snapshot);
    }
}
