package com.example.starter.cutover.api;

import com.example.starter.cutover.service.CutoverService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
/**
 * 割接窗口与回退判据服务 REST 入口。
 */
@RestController
@RequestMapping("/api/cutovers")
public class CutoverController {

    private final CutoverService cutoverService;

    public CutoverController(CutoverService cutoverService) {
        this.cutoverService = cutoverService;
    }

    /** 提交割接申请（按 requestId 幂等）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationResponse apply(@Valid @RequestBody ApplyRequest request) {
        return cutoverService.apply(request);
    }

    /** 查询申请详情（含步骤时序）。 */
    @GetMapping("/{id}")
    public ApplicationResponse get(@PathVariable Long id) {
        return cutoverService.getApplication(id);
    }

    /** 执行影响分析并推进 CREATED -> ANALYZED。 */
    @PostMapping("/{id}/analysis")
    public AnalysisResponse analyze(@PathVariable Long id) {
        return cutoverService.analyze(id);
    }

    /** 复算影响分析清单（只读，不改变状态）。 */
    @GetMapping("/{id}/analysis")
    public AnalysisResponse viewAnalysis(@PathVariable Long id) {
        return cutoverService.viewAnalysis(id);
    }

    /** 复算回退判据命中记录（只读，含回退时点步骤快照）。 */
    @GetMapping("/{id}/rollbacks")
    public RollbackRecordView viewRollback(@PathVariable Long id) {
        return cutoverService.getRollbackRecord(id);
    }

    /** 审批：窗口冲突/优先级抢占在此判定。 */
    @PostMapping("/{id}/approvals")
    public ApplicationResponse approve(@PathVariable Long id) {
        return cutoverService.approve(id);
    }

    /** 进入窗口执行。 */
    @PostMapping("/{id}/execution")
    public ApplicationResponse execute(@PathVariable Long id) {
        return cutoverService.execute(id);
    }

    /** 步骤按序回报（按 requestId 幂等）。 */
    @PostMapping("/{id}/steps/{seq}/reports")
    public ReportResponse report(@PathVariable Long id, @PathVariable Integer seq,
                                 @Valid @RequestBody ReportRequest request) {
        return cutoverService.report(id, seq, request);
    }

    /** 巡检复算：验证点恰超时与窗口超期自动回退。 */
    @PostMapping("/{id}/evaluations")
    public RollbackResponse evaluate(@PathVariable Long id) {
        return cutoverService.evaluate(id);
    }

    /** 人工强制回退。 */
    @PostMapping("/{id}/rollbacks")
    public RollbackResponse forceRollback(@PathVariable Long id,
                                          @RequestParam(required = false) String reason) {
        return cutoverService.forceRollback(id, reason);
    }

    /** 验证通过后封板（终态，重复封板幂等成功）。 */
    @PostMapping("/{id}/seals")
    public SealResponse seal(@PathVariable Long id) {
        return cutoverService.seal(id);
    }
}
