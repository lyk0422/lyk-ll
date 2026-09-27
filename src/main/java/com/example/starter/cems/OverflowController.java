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

import com.example.starter.cems.OverflowTypes.AnomalyView;
import com.example.starter.cems.OverflowTypes.AveragesView;
import com.example.starter.cems.OverflowTypes.HourReportRequest;
import com.example.starter.cems.OverflowTypes.HourReportResult;
import com.example.starter.cems.OverflowTypes.PermitUpsertRequest;
import com.example.starter.cems.OverflowTypes.PermitView;
import com.example.starter.cems.OverflowTypes.RainfallCorrectionRequest;
import com.example.starter.cems.OverflowTypes.RainfallCorrectionResult;
import com.example.starter.cems.OverflowTypes.ReviewFreezeRequest;
import com.example.starter.cems.OverflowTypes.ReviewView;

/**
 * 雨季溢流证据与双口径统计 HTTP 入口（cems-02 新增）。
 * 全部写操作以 requestId 幂等；许可与雨量修改携带 expectedVersion 乐观并发。
 */
@RestController
@RequestMapping("/api/cems/overflow")
public class OverflowController {

    private final OverflowService service;

    public OverflowController(OverflowService service) {
        this.service = service;
    }

    /** 新增溢流许可版本：expectedVersion 为当前最新版本（无许可传 0），成功变更加 1。 */
    @PostMapping("/permits")
    public PermitView upsertPermit(@RequestBody PermitUpsertRequest req) {
        return service.upsertPermit(req);
    }

    /** 现行溢流许可。 */
    @GetMapping("/outlets/{code}/permit")
    public PermitView currentPermit(@PathVariable String code) {
        return service.getCurrentPermit(code);
    }

    /** 小时数据批量上报：UTC 整点，关联雨量与溢流标记；缺雨量只能待核。 */
    @PostMapping("/hours")
    public HourReportResult reportHours(@RequestBody HourReportRequest req) {
        return service.reportHours(req);
    }

    /** 雨量更正：复核冻结前重算标记，冻结后只登记独立调差。 */
    @PostMapping("/rainfall-corrections")
    public RainfallCorrectionResult correctRainfall(@RequestBody RainfallCorrectionRequest req) {
        return service.correctRainfall(req);
    }

    /** 日结复核冻结：冻结所用许可版本、雨量版本与双口径结果。 */
    @PostMapping("/reviews")
    public ReviewView freezeReview(@RequestBody ReviewFreezeRequest req) {
        return service.freezeReview(req);
    }

    /** 日结复核快照查询。 */
    @GetMapping("/outlets/{code}/reviews/{day}")
    public ReviewView review(@PathVariable String code, @PathVariable String day) {
        return service.getReview(code, parseDay(day));
    }

    /** 双口径均值查询：正常排放口径仅排除证据齐全的合规溢流小时，全部排放口径不排除。 */
    @GetMapping("/outlets/{code}/averages")
    public AveragesView averages(@PathVariable String code,
                                 @RequestParam String from,
                                 @RequestParam String to) {
        return service.averages(code, parseInstant(from, "from"), parseInstant(to, "to"));
    }

    /** 专项异常列表：连续合规溢流段超许可最长连续小时数。 */
    @GetMapping("/outlets/{code}/anomalies")
    public List<AnomalyView> anomalies(@PathVariable String code) {
        return service.listAnomalies(code);
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
