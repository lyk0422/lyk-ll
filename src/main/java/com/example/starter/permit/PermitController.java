package com.example.starter.permit;

import com.example.starter.permit.PermitDtos.IssuePermitRequest;
import com.example.starter.permit.PermitDtos.PermitResponse;
import com.example.starter.permit.PermitDtos.ReportResponse;
import com.example.starter.permit.PermitDtos.SubmitReportRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 作业许可与回传接口。
 */
@RestController
@RequestMapping("/api/permits")
public class PermitController {

    private final PermitService permitService;

    public PermitController(PermitService permitService) {
        this.permitService = permitService;
    }

    @PostMapping
    public PermitResponse issue(@Valid @RequestBody IssuePermitRequest request) {
        return permitService.issue(request);
    }

    @PostMapping("/{permitId}/reports")
    public ReportResponse report(@PathVariable long permitId,
            @Valid @RequestBody SubmitReportRequest request) {
        return permitService.report(permitId, request);
    }
}
