package com.github.xingjianluo.riskengine.score;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 当前生效规则集持有者。
 *
 * <p>快照语义：{@link #current()} 读取的是 volatile 引用，调用方拿到的是
 * 不可变规则集对象；{@link #update} 原子替换引用。因此规则更新与评分/告警
 * 查询并发发生时，每次查询要么完整使用旧规则、要么完整使用新规则，
 * 绝不会看到"半个新规则集"的中间状态。</p>
 */
@Component
public class RuleSetProvider {

    private static final Logger log = LoggerFactory.getLogger(RuleSetProvider.class);

    private volatile RiskRuleSet current = RiskRuleSet.defaultRules();

    public RiskRuleSet current() {
        return current;
    }

    public RiskRuleSet update(RiskRuleSet newRules) {
        RiskRuleSet previous = current;
        current = newRules;
        log.info("rule set updated: version {} -> {}", previous.version(), newRules.version());
        return newRules;
    }
}
