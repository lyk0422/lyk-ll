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

import com.example.starter.cems.SubstituteTypes.AdjustmentView;
import com.example.starter.cems.SubstituteTypes.EffectiveAveragesView;
import com.example.starter.cems.SubstituteTypes.FreezeRequest;
import com.example.starter.cems.SubstituteTypes.FreezeView;
import com.example.starter.cems.SubstituteTypes.LabReportRequest;
import com.example.starter.cems.SubstituteTypes.LabReportView;
import com.example.starter.cems.SubstituteTypes.PermitConfirmRequest;
import com.example.starter.cems.SubstituteTypes.PermitUpsertRequest;
import com.example.starter.cems.SubstituteTypes.PermitView;
import com.example.starter.cems.SubstituteTypes.SubstituteExceedanceView;
import com.example.starter.cems.SubstituteTypes.SubstituteReviewRequest;
import com.example.starter.cems.SubstituteTypes.SubstituteReviewResult;
import com.example.starter.cems.SubstituteTypes.SubstituteSubmitRequest;
import com.example.starter.cems.SubstituteTypes.SubstituteSubmitResult;
import com.example.starter.cems.SubstituteTypes.SubstituteView;

/**
 * 检修替代监测与有效监测统计 HTTP 入口（cems-03 新增）。
 * 全部写操作以 requestId 幂等；许可与替代修改携带 expectedVersion 乐观并发。
 */
@RestController
@RequestMapping("/api/cems/substitute")
public class SubstituteController {

    private final SubstituteService service;

    public SubstituteController(SubstituteService service) {
        this.service = service;
    }

    /** 新增检修许可版本：expectedVersion 为当前最新版本（无许可传 0），成功变更加 1。 */
    @PostMapping("/permits")
    public PermitView upsertPermit(@RequestBody PermitUpsertRequest req) {
        return service.upsertPermit(req);
    }

    /** 检修许可确认：两名不同人员确认后生效。 */
    @PostMapping("/permits/confirm")
    public PermitView confirmPermit(@RequestBody PermitConfirmRequest req) {
        return service.confirmPermit(req);
    }

    /** 最新检修许可（含已确认人员）。 */
    @GetMapping("/outlets/{code}/permit")
    public PermitView currentPermit(@PathVariable String code) {
        return service.getCurrentPermit(code);
    }

    /** 合成检验报告登记：报告绑定排口与 UTC 整点。 */
    @PostMapping("/reports")
    public LabReportView registerReport(@RequestBody LabReportRequest req) {
        return service.registerReport(req);
    }

    /** 替代小时提交/更正：缺机测小时、同排口同小时报告、整小时落窗；版本从 1 起。 */
    @PostMapping("/substitutes")
    public SubstituteSubmitResult submitSubstitute(@RequestBody SubstituteSubmitRequest req) {
        return service.submitSubstitute(req);
    }

    /** 替代复核：复核人须不同于提交人；替代超标按原阈值追加独立超标来源。 */
    @PostMapping("/substitutes/review")
    public SubstituteReviewResult reviewSubstitute(@RequestBody SubstituteReviewRequest req) {
        return service.reviewSubstitute(req);
    }

    /** 替代小时台账查询（替代复核留痕）。 */
    @GetMapping("/outlets/{code}/substitutes")
    public List<SubstituteView> substitutes(@PathVariable String code) {
        return service.listSubstitutes(code);
    }

    /** 有效监测均值：机测优先、无机测取有效替代，分母/缺失/来源明示，空集合无结果。 */
    @GetMapping("/outlets/{code}/averages")
    public EffectiveAveragesView averages(@PathVariable String code,
                                          @RequestParam String from,
                                          @RequestParam String to) {
        return service.averages(code, parseInstant(from, "from"), parseInstant(to, "to"));
    }

    /** 有效监测日结冻结：冻结当前来源选择及替代版本。 */
    @PostMapping("/freezes")
    public FreezeView freeze(@RequestBody FreezeRequest req) {
        return service.freeze(req);
    }

    /** 日结冻结快照查询。 */
    @GetMapping("/outlets/{code}/freezes/{day}")
    public FreezeView freezeView(@PathVariable String code, @PathVariable String day) {
        return service.getFreeze(code, parseDay(day));
    }

    /** 独立调差链查询：冻结后补机测/更正替代只形成调差，不重写冻结均值。 */
    @GetMapping("/outlets/{code}/adjustments")
    public List<AdjustmentView> adjustments(@PathVariable String code, @RequestParam String day) {
        return service.listAdjustments(code, parseDay(day));
    }

    /** 替代超标独立来源查询：只留证不能删除。 */
    @GetMapping("/outlets/{code}/exceedances")
    public List<SubstituteExceedanceView> exceedances(@PathVariable String code) {
        return service.listExceedances(code);
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
