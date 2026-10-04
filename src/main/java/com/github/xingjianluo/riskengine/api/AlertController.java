package com.github.xingjianluo.riskengine.api;

import com.github.xingjianluo.riskengine.alert.AlertService;
import com.github.xingjianluo.riskengine.score.RiskScoreResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /** 不带 version 用当前规则集；带 version 按历史版本复算（版本不存在返回 404）。 */
    @GetMapping
    public List<RiskScoreResult> alerts(@RequestParam(required = false) Integer version) {
        return alertService.alertsAboveThreshold(version);
    }
}
