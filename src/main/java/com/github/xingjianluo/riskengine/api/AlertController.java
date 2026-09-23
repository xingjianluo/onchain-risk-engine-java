package com.github.xingjianluo.riskengine.api;

import com.github.xingjianluo.riskengine.alert.AlertService;
import com.github.xingjianluo.riskengine.score.RiskScoreResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public List<RiskScoreResult> alerts() {
        return alertService.alertsAboveThreshold();
    }
}
