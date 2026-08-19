package com.lrj.recon.batch.web;

import com.lrj.recon.batch.service.ReversalExecutionService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B3 · 冲正执行 API。执行真实资金动作(仅 CONFIRMED 可执行)→ 需最高权限 {@code recon.launch}
 * (由 CasdoorSecurityConfig matcher 约束);与审批(B5,recon.dispose)是独立控制点。
 */
@RestController
@RequestMapping("/recon/reversal-executions")
public class ReversalExecutionController {

    private final ReversalExecutionService executions;

    public ReversalExecutionController(ReversalExecutionService executions) {
        this.executions = executions;
    }

    @PostMapping("/{reversalId}/execute")
    public ReversalExecutionService.Result execute(@PathVariable("reversalId") String reversalId,
                                                   @RequestParam(name = "operator", required = false) String operator) {
        return executions.execute(reversalId, operator);
    }
}
