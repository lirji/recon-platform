package com.lrj.recon.batch.web;

import com.lrj.recon.batch.ods.BenefitOdsEvent;
import com.lrj.recon.batch.ods.BenefitOdsIngestionService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/recon/benefit-ods")
public class BenefitOdsReplayController {
    private final BenefitOdsIngestionService ingestion;
    public BenefitOdsReplayController(BenefitOdsIngestionService ingestion) { this.ingestion = ingestion; }

    @PostMapping("/replay")
    public BenefitOdsIngestionService.IngestionResult replay(@RequestBody BenefitOdsEvent event) {
        return ingestion.ingest(event);
    }
}
