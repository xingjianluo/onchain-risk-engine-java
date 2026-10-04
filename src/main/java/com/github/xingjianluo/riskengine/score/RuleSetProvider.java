package com.github.xingjianluo.riskengine.score;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则版本注册表 + 当前生效规则集持有者。
 *
 * <p>版本留存：每个发布过的规则版本都保存在 {@code publishedVersions} 中，
 * 只追加、不覆盖、不删除；积累几百个版本也只是几百个不可变 record，
 * 按版本号 O(1) 取回，可随时用历史版本复算当时的结论。</p>
 *
 * <p>追加式发布：{@link #update} 在锁内校验"新版本号必须大于已发布的最新版本号"，
 * 同一版本号重复发布（无论内容是否相同）一律以
 * {@link RuleVersionConflictException} 拒绝，历史内容不会被覆盖。</p>
 *
 * <p>快照语义：{@link #current()} 读取的是 volatile 引用，调用方拿到的是
 * 不可变规则集对象；发布是原子替换引用。因此规则发布与评分/告警查询并发时，
 * 每次查询要么完整使用旧版本、要么完整使用新版本，绝不新旧混用；
 * 且当前评分/告警查询路径完全不触碰版本表，版本累积不会拖慢查询。</p>
 */
@Component
public class RuleSetProvider {

    private static final Logger log = LoggerFactory.getLogger(RuleSetProvider.class);

    /** 全部已发布版本：version -> 不可变规则集。 */
    private final ConcurrentHashMap<Integer, RiskRuleSet> publishedVersions = new ConcurrentHashMap<>();

    private volatile RiskRuleSet current;
    private volatile int latestVersion;

    public RuleSetProvider() {
        RiskRuleSet v1 = RiskRuleSet.defaultRules();
        publishedVersions.put(v1.version(), v1);
        this.current = v1;
        this.latestVersion = v1.version();
    }

    /** 当前生效规则集（volatile 读，查询热路径，O(1)）。 */
    public RiskRuleSet current() {
        return current;
    }

    /**
     * 追加发布新版本。版本号必须严格大于当前最新版本号，
     * 否则抛出 {@link RuleVersionConflictException}，已发布内容保持不变。
     */
    public synchronized RiskRuleSet update(RiskRuleSet newRules) {
        int version = newRules.version();
        if (publishedVersions.containsKey(version)) {
            throw new RuleVersionConflictException(
                    "rule version " + version + " already published; versions are append-only");
        }
        if (version < latestVersion) {
            throw new RuleVersionConflictException(
                    "rule version " + version + " is behind latest published version "
                            + latestVersion + "; versions must move forward");
        }
        RiskRuleSet previous = current;
        publishedVersions.put(version, newRules);
        current = newRules;
        latestVersion = version;
        log.info("rule set published: version {} -> {} (total published={})",
                previous.version(), version, publishedVersions.size());
        return newRules;
    }

    /** 按版本号取回任一历史规则集；版本不存在时抛 IllegalArgumentException。 */
    public RiskRuleSet version(int version) {
        RiskRuleSet rules = publishedVersions.get(version);
        if (rules == null) {
            throw new IllegalArgumentException("unknown rule version: " + version
                    + " (published: " + versions() + ")");
        }
        return rules;
    }

    /** 全部已发布版本号（升序）。 */
    public List<Integer> versions() {
        return publishedVersions.keySet().stream().sorted().toList();
    }
}
