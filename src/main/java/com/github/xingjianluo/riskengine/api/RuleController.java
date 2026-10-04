package com.github.xingjianluo.riskengine.api;

import com.github.xingjianluo.riskengine.score.RiskRuleSet;
import com.github.xingjianluo.riskengine.score.RuleSetProvider;
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

    /** 全部已发布版本号（升序）。 */
    @GetMapping("/versions")
    public List<Integer> versions() {
        return ruleSetProvider.versions();
    }

    /** 按版本号取回任一历史规则集；版本不存在返回 400。 */
    @GetMapping("/{version}")
    public RiskRuleSet version(@PathVariable int version) {
        return ruleSetProvider.version(version);
    }

    /**
     * 追加发布新版本规则集。版本号必须大于已发布的最新版本号；
     * 同版本号重复发布或回退版本号返回 409，历史版本内容不会被覆盖。
     */
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
