package com.github.xingjianluo.riskengine.api;

import com.github.xingjianluo.riskengine.score.RiskScoreResult;
import com.github.xingjianluo.riskengine.score.RiskScoringService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scores")
public class ScoreController {

    private final RiskScoringService scoringService;

    public ScoreController(RiskScoringService scoringService) {
        this.scoringService = scoringService;
    }

    /** 不带 version 用当前规则集；带 version 按历史版本复算（版本不存在返回 404）。 */
    @GetMapping("/{address}")
    public RiskScoreResult score(@PathVariable String address,
                                 @RequestParam(required = false) Integer version) {
        return scoringService.score(address, version);
    }
}
