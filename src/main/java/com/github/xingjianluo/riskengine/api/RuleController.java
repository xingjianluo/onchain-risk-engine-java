package com.github.xingjianluo.riskengine.api;

import com.github.xingjianluo.riskengine.score.RiskRuleSet;
import com.github.xingjianluo.riskengine.score.RuleSetProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PutMapping
    public RiskRuleSet update(@RequestBody RiskRuleSet rules) {
        RiskRuleSet validated = RiskRuleSet.validated(
                rules.version(),
                rules.largeAmountThreshold(),
                rules.fanOutThreshold(),
                rules.fanInThreshold(),
                rules.largeAmountWeight(),
                rules.fanOutWeight(),
                rules.fanInWeight(),
                rules.alertScoreThreshold());
        return ruleSetProvider.update(validated);
    }
}
