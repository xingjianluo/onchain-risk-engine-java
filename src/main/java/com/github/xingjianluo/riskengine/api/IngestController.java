package com.github.xingjianluo.riskengine.api;

import com.github.xingjianluo.riskengine.ingest.EventIngestionService;
import com.github.xingjianluo.riskengine.model.IngestResult;
import com.github.xingjianluo.riskengine.model.TransactionEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class IngestController {

    private final EventIngestionService ingestionService;

    public IngestController(EventIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public IngestResult ingest(@RequestBody TransactionEvent event) {
        return ingestionService.ingest(event);
    }

    @PostMapping("/batch")
    public List<IngestResult> ingestBatch(@RequestBody List<TransactionEvent> events) {
        return events.stream().map(ingestionService::ingest).toList();
    }
}
