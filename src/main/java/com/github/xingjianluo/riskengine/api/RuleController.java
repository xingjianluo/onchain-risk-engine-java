package com.github.xingjianluo.riskengine.api;

import com.github.xingjianluo.riskengine.score.RiskRuleSet;
import com.github.xingjianluo.riskengine.score.RuleSetProvider;
import com.github.xingjianluo.riskengine.score.RuleVersionNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleSetProvider ruleSetProvider;

    public RuleController(RuleSetProvider ruleSetProvider) {
        this.ruleSetProvider = ruleSetProvider;
    }

    @GetMapping
    public RiskRuleSet current() {
        return ruleSetProvider.current();
    }

    /** 全部已发布版本号（升序），用于发现可复算的历史版本。 */
    @GetMapping("/versions")
    public List<Integer> versions() {
        return ruleSetProvider.versions();
    }

    /** 按版本号取回历史规则集；未发布过返回 404。 */
    @GetMapping("/{version}")
    public RiskRuleSet byVersion(@PathVariable int version) {
        RiskRuleSet rules = ruleSetProvider.version(version);
        if (rules == null) {
            throw new RuleVersionNotFoundException(version);
        }
        return rules;
    }

    /**
     * 发布新版本规则集。版本号必须大于当前生效版本且从未发布过：
     * 重复版本号返回 409，版本回退返回 400，非法参数返回 400。
     */
    @PutMapping
    public RiskRuleSet publish(@RequestBody RiskRuleSet rules) {
        RiskRuleSet validated = RiskRuleSet.validated(
                rules.version(),
                rules.largeAmountThreshold(),
                rules.fanOutThreshold(),
                rules.fanInThreshold(),
                rules.largeAmountWeight(),
                rules.fanOutWeight(),
                rules.fanInWeight(),
                rules.alertScoreThreshold());
        return ruleSetProvider.publish(validated);
    }
}
