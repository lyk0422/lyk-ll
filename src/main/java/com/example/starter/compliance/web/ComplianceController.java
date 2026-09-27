package com.example.starter.compliance.web;

import com.example.starter.compliance.service.ComplianceService;
import com.example.starter.compliance.service.Results;
import com.example.starter.compliance.service.ServiceResult;
import com.example.starter.compliance.web.Requests.ConfirmCleaningRequest;
import com.example.starter.compliance.web.Requests.ControlledProductionRequest;
import com.example.starter.compliance.web.Requests.FileFilingRequest;
import com.example.starter.compliance.web.Requests.FilingRefRequest;
import com.example.starter.compliance.web.Requests.FormulaRefRequest;
import com.example.starter.compliance.web.Requests.ProductionReportRequest;
import com.example.starter.compliance.web.Requests.RegisterLineRequest;
import com.example.starter.compliance.web.Requests.StandardUpdateRequest;
import com.example.starter.compliance.web.Requests.SubmitFormulaRequest;
import com.example.starter.compliance.web.Requests.UpsertAllergensRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配方合规服务 HTTP 入口：
 * 提交→审查→批准→量产备案；标准更新复审观察、逾期巡检、注销、量产上报、版本轨迹查询。
 */
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final ComplianceService service;

    public ComplianceController(ComplianceService service) {
        this.service = service;
    }

    /** 提交配方（SUBMITTED），requestId 幂等。 */
    @PostMapping("/formulas")
    public ResponseEntity<Results.FormulaView> submit(@RequestBody SubmitFormulaRequest request) {
        return respond(service.submitFormula(request));
    }

    /** 对已提交配方做合规审查，输出逐项判定明细；任一违例整单拒绝。 */
    @PostMapping("/formulas/review")
    public ResponseEntity<Results.ReviewView> review(@RequestBody FormulaRefRequest request) {
        return respond(service.reviewFormula(request));
    }

    /** 批准审查通过的配方并冻结版本快照；同事务暂停旧版本有效备案。 */
    @PostMapping("/formulas/approve")
    public ResponseEntity<Results.FormulaView> approve(@RequestBody FormulaRefRequest request) {
        return respond(service.approveFormula(request));
    }

    @GetMapping("/formulas/{formulaId}")
    public Results.FormulaView formula(@PathVariable long formulaId) {
        return service.getFormula(formulaId);
    }

    /** 对批准版本做量产备案；同一产品同一时刻仅一个有效备案版本。 */
    @PostMapping("/filings")
    public ResponseEntity<Results.FilingView> file(@RequestBody FileFilingRequest request) {
        return respond(service.fileFiling(request));
    }

    /** 备案复审：复审观察期 90 个 UTC 自然日内（恰第 90 日受理）按新标准复算。 */
    @PostMapping("/filings/review")
    public ResponseEntity<Results.FilingReviewView> reviewFiling(
            @RequestBody FilingRefRequest request) {
        return respond(service.reviewFiling(request));
    }

    /** 注销备案（终态）；重复注销幂等成功。 */
    @PostMapping("/filings/cancel")
    public ResponseEntity<Results.FilingView> cancel(@RequestBody FilingRefRequest request) {
        return respond(service.cancelFiling(request));
    }

    @GetMapping("/filings/{filingId}")
    public Results.FilingView filing(@PathVariable long filingId) {
        return service.getFiling(filingId);
    }

    /** 量产记录上报：注销/暂停/逾期备案一律拒绝。 */
    @PostMapping("/production-records")
    public ResponseEntity<Results.ProductionView> reportProduction(
            @RequestBody ProductionReportRequest request) {
        return respond(service.reportProduction(request));
    }

    /** 添加剂限量标准更新（插入或更新）；受影响的有效备案进入复审观察。 */
    @PostMapping("/standards")
    public ResponseEntity<Results.StandardView> updateStandard(
            @RequestBody StandardUpdateRequest request) {
        return respond(service.updateStandard(request));
    }

    /** 逾期巡检：超过第 90 个 UTC 自然日未复审的观察备案自动暂停。 */
    @PostMapping("/filings/sweep-overdue")
    public Results.SweepView sweepOverdue() {
        return service.sweepOverdue();
    }

    /** 产品版本轨迹：配方版本与备案状态链，供轨迹复算与重复查询核对。 */
    @GetMapping("/products/{productCode}/trajectory")
    public Results.TrajectoryView trajectory(@PathVariable String productCode) {
        return service.getTrajectory(productCode);
    }

    /** 登记生产线：占用版本从 1 开始，lineCode 唯一。 */
    @PostMapping("/lines")
    public ResponseEntity<Results.LineView> registerLine(
            @RequestBody RegisterLineRequest request) {
        return respond(service.registerLine(request));
    }

    @GetMapping("/lines/{lineCode}")
    public Results.LineView line(@PathVariable String lineCode) {
        return service.getLine(lineCode);
    }

    /** 登记/变更配方版本过敏原集合：变更携带 expectedVersion 乐观锁。 */
    @PostMapping("/formulas/allergens")
    public ResponseEntity<Results.AllergenView> upsertAllergens(
            @RequestBody UpsertAllergensRequest request) {
        return respond(service.upsertAllergens(request));
    }

    @GetMapping("/formulas/{formulaId}/allergens")
    public Results.AllergenView allergens(@PathVariable long formulaId) {
        return service.getAllergens(formulaId);
    }

    /** 确认清线凭证：两名不同人员共同确认，固定线路、清线时刻与占用版本，2 小时内有效。 */
    @PostMapping("/cleaning-vouchers")
    public ResponseEntity<Results.VoucherView> confirmCleaning(
            @RequestBody ConfirmCleaningRequest request) {
        return respond(service.confirmCleaning(request));
    }

    @GetMapping("/cleaning-vouchers/{voucherId}")
    public Results.VoucherView voucher(@PathVariable long voucherId) {
        return service.getVoucher(voucherId);
    }

    /**
     * 受控量产入口：声明生产线与占用版本；前批含新批未声明过敏原时必须引用有效清线凭证，
     * 不得绕过门禁；旧 /production-records 入口（未指定生产线）保持兼容。
     */
    @PostMapping("/lines/production-records")
    public ResponseEntity<Results.ControlledProductionView> controlledProduction(
            @RequestBody ControlledProductionRequest request) {
        return respond(service.controlledProduction(request));
    }

    private static <T> ResponseEntity<T> respond(ServiceResult<T> result) {
        // 首次与重放均返回首次状态码（如逾期自动暂停首次即 409）；重放标记见响应头与响应体 replayed。
        return ResponseEntity.status(result.status())
                .header("X-Idempotent-Replay", String.valueOf(result.replayed()))
                .body(result.body());
    }
}
