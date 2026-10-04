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

    /**
     * 查询钱包评分。不带 version 参数时按当前生效规则集评分（既有行为不变）；
     * 带 version 参数时按指定历史版本规则集复算当时的结论。
     */
    @GetMapping("/{address}")
    public RiskScoreResult score(@PathVariable String address,
                                 @RequestParam(required = false) Integer version) {
        return version == null ? scoringService.score(address) : scoringService.score(address, version);
    }
}
