package com.github.xingjianluo.onchainriskenginejava.rules;

import java.util.List;

/**
 * 不可变规则集：一个版本号绑定一组规则定义。
 */
public record RiskRuleSet(
        String version,
        List<RiskRule> rules
) {
}
