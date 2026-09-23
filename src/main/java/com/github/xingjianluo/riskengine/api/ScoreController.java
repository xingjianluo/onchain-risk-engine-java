package com.github.xingjianluo.riskengine.api;

import com.github.xingjianluo.riskengine.score.RiskScoreResult;
import com.github.xingjianluo.riskengine.score.RiskScoringService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scores")
public class ScoreController {

    private final RiskScoringService scoringService;

    public ScoreController(RiskScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @GetMapping("/{address}")
    public RiskScoreResult score(@PathVariable String address) {
        return scoringService.score(address);
    }
}
