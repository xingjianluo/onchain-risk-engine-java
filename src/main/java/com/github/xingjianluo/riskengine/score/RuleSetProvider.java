package com.github.xingjianluo.riskengine.score;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则集持有者：当前生效版本 + 全部历史版本。
 *
 * <p>版本留存：每个发布过的版本都保存在 {@code history} 中，只追加、不覆盖、
 * 不删除；只凭版本号即可取回当时那套规则，复现历史结论。历史查找是
 * {@link ConcurrentHashMap} 的 O(1) 读取，积累几百个版本也不会拖慢
 * 当前评分与告警查询（它们只读 {@code current} 这一个 volatile 引用）。</p>
 *
 * <p>快照语义：{@link #current()} 读取的是 volatile 引用，调用方拿到的是
 * 不可变规则集对象；{@link #publish} 原子替换引用。因此规则发布与评分/告警
 * 查询并发发生时，每次查询要么完整使用旧规则、要么完整使用新规则，
 * 绝不会看到"半个新规则集"的中间状态。</p>
 *
 * <p>追加约束：发布版本号必须大于当前生效版本号，且从未被发布过；
 * 检查与切换在同一把锁内完成，并发发布下也不会出现版本回退或覆盖。</p>
 */
@Component
public class RuleSetProvider {

    private static final Logger log = LoggerFactory.getLogger(RuleSetProvider.class);

    private final ConcurrentHashMap<Integer, RiskRuleSet> history = new ConcurrentHashMap<>();
    private final Object publishLock = new Object();

    private volatile RiskRuleSet current;

    public RuleSetProvider() {
        RiskRuleSet initial = RiskRuleSet.defaultRules();
        history.put(initial.version(), initial);
        current = initial;
    }

    /** 当前生效规则集（不可变对象，可安全地长时间持有）。 */
    public RiskRuleSet current() {
        return current;
    }

    /** 取回任意历史版本；未发布过返回 null。 */
    public RiskRuleSet version(int version) {
        return history.get(version);
    }

    /** 全部已发布版本号，升序。 */
    public List<Integer> versions() {
        return history.keySet().stream().sorted().toList();
    }

    /**
     * 发布新版本：只追加，不覆盖。
     *
     * @throws DuplicateRuleVersionException 版本号已发布过
     * @throws IllegalArgumentException      版本号未前进（不大于当前生效版本）
     */
    public RiskRuleSet publish(RiskRuleSet newRules) {
        synchronized (publishLock) {
            int version = newRules.version();
            if (history.containsKey(version)) {
                throw new DuplicateRuleVersionException(version);
            }
            if (version <= current.version()) {
                throw new IllegalArgumentException(
                        "rule set version must move forward: current=" + current.version()
                                + " rejected=" + version);
            }
            history.put(version, newRules);
            RiskRuleSet previous = current;
            current = newRules;
            log.info("rule set published: version {} -> {} (retained versions: {})",
                    previous.version(), newRules.version(), history.size());
            return newRules;
        }
    }
}
