package com.lrj.recon.batch.web;

import com.lrj.recon.batch.service.BenefitRemediationService;
import com.lrj.recon.core.domain.model.RemediationSuggestion;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/recon/benefit-remediations")
public class BenefitRemediationController {
    private final BenefitRemediationService service;
    public BenefitRemediationController(BenefitRemediationService service) { this.service = service; }

    @PostMapping
    public RemediationSuggestion propose(@RequestBody BenefitRemediationService.ProposeCommand command) {
        return service.propose(command);
    }

    @PostMapping("/{suggestionId}/approve")
    public RemediationSuggestion approve(@PathVariable String suggestionId, @RequestBody Decision decision) {
        return service.approve(decision.tenantId(), suggestionId, decision.approvalRef());
    }

    @PostMapping("/{suggestionId}/reject")
    public RemediationSuggestion reject(@PathVariable String suggestionId, @RequestBody Decision decision) {
        return service.reject(decision.tenantId(), suggestionId, decision.approvalRef());
    }

    public record Decision(String tenantId, String approvalRef) {}
}
