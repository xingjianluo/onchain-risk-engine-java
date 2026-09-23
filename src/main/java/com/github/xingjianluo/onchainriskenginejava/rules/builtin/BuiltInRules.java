package com.github.xingjianluo.onchainriskenginejava.rules.builtin;

import com.github.xingjianluo.onchainriskenginejava.rules.RiskRule;
import com.github.xingjianluo.onchainriskenginejava.rules.RiskRuleSet;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 内置规则集工厂。规则参数集中在此定义：
 * <ul>
 *   <li>{@link #VERSION_V1}：初始规则集，5 条规则，满分上限 100（原始分 30+20+25+15+10）；</li>
 *   <li>{@link #VERSION_V2}：参数收紧的更新版（大额转出阈值更低、扩散阈值更低、快速过账窗口更短），
 *       版本号不同，历史 v1 结论仍可用保留下来的 v1 规则集完整复现。</li>
 * </ul>
 */
public final class BuiltInRules {

    public static final String VERSION_V1 = "builtin-v1";
    public static final String VERSION_V2 = "builtin-v2";

    /** 默认风险名单（本地模拟数据中的 DARK1）。 */
    public static final Set<String> DEFAULT_RISKY_ADDRESSES = Set.of(
            "0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead");

    private BuiltInRules() {
    }

    /** v1 规则集。 */
    public static RiskRuleSet v1(Set<String> riskyAddresses) {
        List<RiskRule> rules = List.of(
                new HighOutgoingVolumeRule(new BigDecimal("100"), 30, VERSION_V1),
                new HighCounterpartyFanoutRule(5, 20, VERSION_V1),
                new RiskyCounterpartyRule(copy(riskyAddresses), 25, VERSION_V1),
                new RapidPassThroughRule(60L * 60 * 1000, new BigDecimal("0.80"), 15, VERSION_V1),
                new InflowOutflowImbalanceRule(new BigDecimal("50"),
                        new BigDecimal("0.20"), 10, VERSION_V1)
        );
        return new RiskRuleSet(VERSION_V1, rules);
    }

    /**
     * v2 规则集（更新后）：大额转出阈值 80、扩散阈值 4、快速过账窗口 30 分钟，
     * 分值结构调整。用于演示“规则更新”。
     */
    public static RiskRuleSet v2(Set<String> riskyAddresses) {
        List<RiskRule> rules = List.of(
                new HighOutgoingVolumeRule(new BigDecimal("80"), 35, VERSION_V2),
                new HighCounterpartyFanoutRule(4, 25, VERSION_V2),
                new RiskyCounterpartyRule(copy(riskyAddresses), 25, VERSION_V2),
                new RapidPassThroughRule(30L * 60 * 1000, new BigDecimal("0.80"), 15, VERSION_V2),
                new InflowOutflowImbalanceRule(new BigDecimal("50"),
                        new BigDecimal("0.20"), 10, VERSION_V2)
        );
        return new RiskRuleSet(VERSION_V2, rules);
    }

    private static Set<String> copy(Set<String> riskyAddresses) {
        return riskyAddresses == null ? Set.of() : Set.copyOf(riskyAddresses);
    }
}
