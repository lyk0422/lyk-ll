package com.example.starter.cems;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.cems.MaintenanceTypes.EffectiveAveragesView;
import com.example.starter.cems.MaintenanceTypes.LabReportRequest;
import com.example.starter.cems.MaintenanceTypes.LabReportView;
import com.example.starter.cems.MaintenanceTypes.MaintenanceAdjustmentView;
import com.example.starter.cems.MaintenanceTypes.MaintenancePermitRequest;
import com.example.starter.cems.MaintenanceTypes.MaintenancePermitView;
import com.example.starter.cems.MaintenanceTypes.MaintenanceReviewRequest;
import com.example.starter.cems.MaintenanceTypes.MaintenanceReviewView;
import com.example.starter.cems.MaintenanceTypes.ReplacementConfirmRequest;
import com.example.starter.cems.MaintenanceTypes.ReplacementSubmitRequest;
import com.example.starter.cems.MaintenanceTypes.ReplacementView;
import com.example.starter.cems.MaintenanceTypes.SubstituteExceedanceView;

/**
 * 监测仪检修人工替代证据与有效监测统计 HTTP 入口（cems-03 新增）。
 * 许可、检验报告、替代提交/复核与封账均以 requestId 幂等；许可与替代修改携带 expectedVersion 乐观并发。
 */
@RestController
@RequestMapping("/api/cems/maintenance")
public class MaintenanceController {

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    /** 新增检修许可版本：绑定排口及 UTC 半开窗口，须两名不同人员确认。 */
    @PostMapping("/permits")
    public MaintenancePermitView upsertPermit(@RequestBody MaintenancePermitRequest req) {
        return service.upsertPermit(req);
    }

    /** 现行检修许可。 */
    @GetMapping("/outlets/{code}/permit")
    public MaintenancePermitView currentPermit(@PathVariable String code) {
        return service.getCurrentPermit(code);
    }

    /** 登记合成检验报告：同排口、UTC 半开采样窗口；替代小时时窗口必须与该小时精确一致。 */
    @PostMapping("/lab-reports")
    public LabReportView registerLabReport(@RequestBody LabReportRequest req) {
        return service.registerLabReport(req);
    }

    /** 缺机测小时提交替代证据：有限非负浓度＋同排口检验报告＋UTC 整点，须另一人复核才有效。 */
    @PostMapping("/replacements")
    public ReplacementView submitReplacement(@RequestBody ReplacementSubmitRequest req) {
        return service.submitReplacement(req);
    }

    /** 替代复核：复核人必须不同于提交人，复核成功版本生效、旧版本失效。 */
    @PostMapping("/replacements/confirm")
    public ReplacementView confirmReplacement(@RequestBody ReplacementConfirmRequest req) {
        return service.confirmReplacement(req);
    }

    /** 替代复核记录链查询：区间 [from,to) 内全部替代版本（含 PENDING/EFFECTIVE/SUPERSEDED/INVALIDATED）。 */
    @GetMapping("/outlets/{code}/replacements")
    public List<ReplacementView> replacements(@PathVariable String code,
                                              @RequestParam String from,
                                              @RequestParam String to) {
        return service.listReplacements(code, parseInstant(from, "from"), parseInstant(to, "to"));
    }

    /** 有效监测均值：机测优先、无机测才取有效替代；分母、缺失小时与来源明示。 */
    @GetMapping("/outlets/{code}/effective-averages")
    public EffectiveAveragesView effectiveAverages(@PathVariable String code,
                                                   @RequestParam String from,
                                                   @RequestParam String to) {
        return service.effectiveAverages(code, parseInstant(from, "from"), parseInstant(to, "to"));
    }

    /** 有效监测日结封账：冻结当前逐小时选择与替代版本。 */
    @PostMapping("/reviews")
    public MaintenanceReviewView freezeReview(@RequestBody MaintenanceReviewRequest req) {
        return service.freezeReview(req);
    }

    /** 有效监测日结快照查询。 */
    @GetMapping("/outlets/{code}/reviews/{day}")
    public MaintenanceReviewView review(@PathVariable String code, @PathVariable String day) {
        return service.getReview(code, parseDay(day));
    }

    /** 封账后独立调差链查询。 */
    @GetMapping("/outlets/{code}/adjustments")
    public List<MaintenanceAdjustmentView> adjustments(@PathVariable String code, @RequestParam String day) {
        return service.listAdjustments(code, parseDay(day));
    }

    /** 替代超标独立来源链查询（只留证不删除）。 */
    @GetMapping("/outlets/{code}/substitute-exceedances")
    public List<SubstituteExceedanceView> substituteExceedances(@PathVariable String code) {
        return service.listSubstituteExceedances(code);
    }

    private static Instant parseInstant(String text, String field) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException(field + " 必须为 UTC 精确时刻（如 2026-09-26T00:00:00Z）: " + text);
        }
    }

    private static LocalDate parseDay(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("day 必须为 UTC 日期（如 2026-09-26）: " + text);
        }
    }
}
