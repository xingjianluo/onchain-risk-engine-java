package com.github.xingjianluo.onchainriskenginejava.rules;

import com.github.xingjianluo.onchainriskenginejava.rules.builtin.BuiltInRules;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规则集注册表。
 *
 * <p><b>更新语义</b>：规则更新通过整体发布一个不可变 {@link RiskRuleSet} 完成。
 * 发布对读线程是原子的（{@link AtomicReference} 引用交换），进行中的评分/告警查询
 * 要么完整使用旧版本、要么完整使用新版本，不会读到“半截规则集”。</p>
 *
 * <p><b>复现语义</b>：历史版本不可变且永久保留（按版本号索引），给定历史版本号即可
 * 重新计算出当时的结论。版本号重复发布将被拒绝。</p>
 */
@Component
public class RuleRegistry {

    private final AtomicReference<RiskRuleSet> current = new AtomicReference<>();
    private final Map<String, RiskRuleSet> history = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        publish(BuiltInRules.v1(BuiltInRules.DEFAULT_RISKY_ADDRESSES));
    }

    /** 当前生效规则集（永不为 null）。 */
    public RiskRuleSet current() {
        return current.get();
    }

    /** 按版本号取回历史规则集，支持历史结论复现。 */
    public Optional<RiskRuleSet> byVersion(String version) {
        return Optional.ofNullable(history.get(version));
    }

    public List<String> versions() {
        return history.keySet().stream().sorted().toList();
    }

    /**
     * 切回一个已经发布过的历史版本（原子引用交换，不改变历史）。
     * 用于测试复位与“回到旧规则”的运维场景。
     */
    public void activateExisting(String version) {
        RiskRuleSet ruleSet = history.get(version);
        if (ruleSet == null) {
            throw new IllegalArgumentException("规则集版本不存在: " + version);
        }
        current.set(ruleSet);
    }

    /**
     * 原子发布新规则集。
     *
     * @throws IllegalArgumentException 版本号为空、规则为空或版本号已存在
     */
    public void publish(RiskRuleSet ruleSet) {
        if (ruleSet == null || ruleSet.version() == null || ruleSet.version().isBlank()) {
            throw new IllegalArgumentException("规则集版本号不能为空");
        }
        if (ruleSet.rules() == null || ruleSet.rules().isEmpty()) {
            throw new IllegalArgumentException("规则集至少要包含一条规则");
        }
        RiskRuleSet immutable = new RiskRuleSet(ruleSet.version(), List.copyOf(ruleSet.rules()));
        RiskRuleSet previous = history.putIfAbsent(immutable.version(), immutable);
        if (previous != null) {
            throw new IllegalArgumentException("规则集版本号已存在，禁止覆盖: " + immutable.version());
        }
        current.set(immutable);
    }
}
