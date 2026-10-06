package com.example.starter.pest.api;

import com.example.starter.pest.PestReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 虫情上报与确认接口。
 */
@RestController
@RequestMapping("/api/pest-reports")
public class PestReportController {

    private final PestReportService pestReportService;

    public PestReportController(PestReportService pestReportService) {
        this.pestReportService = pestReportService;
    }

    /**
     * 按库区登记虫情上报。
     */
    @PostMapping
    public Results.ReportResult report(@Valid @RequestBody Requests.ReportRequest request) {
        return pestReportService.report(request);
    }

    /**
     * 确认虫情，库区进入处置状态。
     */
    @PostMapping("/{reportId}/confirm")
    public Results.ConfirmResult confirm(@PathVariable long reportId,
                                         @Valid @RequestBody Requests.ConfirmRequest request) {
        return pestReportService.confirm(reportId, request);
    }
}
