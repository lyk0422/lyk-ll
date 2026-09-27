package com.example.starter.cems;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.cems.CemsTypes.OverflowAnomalyView;
import com.example.starter.cems.CemsTypes.OverflowDailySummaryView;
import com.example.starter.cems.CemsTypes.OverflowHourView;
import com.example.starter.cems.CemsTypes.OverflowHourlyRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitCreateRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitUpdateRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitView;
import com.example.starter.cems.CemsTypes.OverflowReviewRequest;
import com.example.starter.cems.CemsTypes.OverflowReviewResult;
import com.example.starter.cems.CemsTypes.RainfallAdjustRequest;
import com.example.starter.cems.CemsTypes.RainfallAdjustResult;
import com.example.starter.cems.CemsTypes.RainfallReportRequest;
import com.example.starter.cems.CemsTypes.RainfallReportResult;

/**
 * 雨季溢流 HTTP 入口（UTC 口径）。新建/修改许可、雨量上报、溢流小时申报、
 * 标记复核冻结（双口径日结）、冻结后独立调差。写操作均以 requestId 幂等（雨量上报除外，按版本号乐观并发）。
 */
@RestController
@RequestMapping("/api/cems/overflow")
public class OverflowController {

    private final OverflowService service;

    public OverflowController(OverflowService service) {
        this.service = service;
    }

    /** 新建溢流许可版本（首版本固定为 1）。 */
    @PostMapping("/permits")
    public OverflowPermitView createPermit(@RequestBody OverflowPermitCreateRequest req) {
        return service.createPermit(req);
    }

    /** 修改溢流许可：携带 expectedVersion，成功后版本加 1。 */
    @PostMapping("/permits/update")
    public OverflowPermitView updatePermit(@RequestBody OverflowPermitUpdateRequest req) {
        return service.updatePermit(req);
    }

    @GetMapping("/permits/{code}")
    public List<OverflowPermitView> listPermits(@PathVariable String code) {
        return service.listPermits(code);
    }

    /** 上报/更正某 UTC 整点小时雨量（按版本号乐观并发）。 */
    @PostMapping("/rainfall")
    public RainfallReportResult reportRainfall(@RequestBody RainfallReportRequest req) {
        return service.reportRainfall(req);
    }

    /** 上报 UTC 整点小时排放数据并申报溢流。 */
    @PostMapping("/hourly")
    public OverflowHourView reportHour(@RequestBody OverflowHourlyRequest req) {
        return service.reportOverflowHour(req);
    }

    @GetMapping("/hourly/{code}")
    public OverflowHourView getHour(@PathVariable String code,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hourUtc) {
        return service.getOverflowHour(code, hourUtc);
    }

    /** 标记复核：冻结许可/雨量版本并产出该 UTC 日双口径日结。 */
    @PostMapping("/review")
    public OverflowReviewResult review(@RequestBody OverflowReviewRequest req) {
        return service.review(req);
    }

    /** 冻结后独立雨量调差：不改日结快照。 */
    @PostMapping("/rainfall/adjust")
    public RainfallAdjustResult adjust(@RequestBody RainfallAdjustRequest req) {
        return service.adjustRainfall(req);
    }

    @GetMapping("/daily-summary/{code}")
    public OverflowDailySummaryView dailySummary(@PathVariable String code,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dayUtc) {
        return service.getDailySummary(code, dayUtc);
    }

    @GetMapping("/anomalies/{code}")
    public List<OverflowAnomalyView> anomalies(@PathVariable String code) {
        return service.listAnomalies(code);
    }
}
