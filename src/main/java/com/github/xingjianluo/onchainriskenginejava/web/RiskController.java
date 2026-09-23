package com.github.xingjianluo.onchainriskenginejava.web;

import com.github.xingjianluo.onchainriskenginejava.model.IngestionResult;
import com.github.xingjianluo.onchainriskenginejava.model.RiskAlert;
import com.github.xingjianluo.onchainriskenginejava.model.RiskScore;
import com.github.xingjianluo.onchainriskenginejava.model.TransactionEvent;
import com.github.xingjianluo.onchainriskenginejava.service.RiskEngineService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 本地 HTTP 接口：摄入、评分、告警、管理。
 */
@RestController
@RequestMapping("/api/risk")
public class RiskController {

    private final RiskEngineService service;

    public RiskController(RiskEngineService service) {
        this.service = service;
    }

    @PostMapping("/events")
    public IngestionResult ingest(@RequestBody IngestRequest request) {
        return service.ingest(request.toEvent());
    }

    @PostMapping("/events/batch")
    public List<IngestionResult> ingestBatch(@RequestBody List<IngestRequest> requests) {
        return requests.stream().map(r -> service.ingest(r.toEvent())).toList();
    }

    @GetMapping("/score/{address}")
    public RiskScore score(@PathVariable String address) {
        return service.score(address);
    }

    @GetMapping("/alerts")
    public List<RiskAlert> alerts(@RequestParam(defaultValue = "60") int threshold) {
        return service.alerts(threshold);
    }
}
