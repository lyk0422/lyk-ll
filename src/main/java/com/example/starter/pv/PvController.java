package com.example.starter.pv;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.pv.dto.CaseView;
import com.example.starter.pv.dto.CloseCaseResponse;
import com.example.starter.pv.dto.FollowUpRequest;
import com.example.starter.pv.dto.InitialReportRequest;
import com.example.starter.pv.dto.ReportResponse;
import com.example.starter.pv.dto.SubmissionBatchRequest;
import com.example.starter.pv.dto.SubmissionBatchResponse;

/**
 * 药物警戒个例管理 API：初报、随访、关闭、监管递交批次与查询。
 */
@RestController
@RequestMapping("/api/pv")
public class PvController {

    private final PvCaseService service;

    public PvController(PvCaseService service) {
        this.service = service;
    }

    /**
     * 提交初报；已存在活动个例时按随访并入，已关闭时新建关联个例。
     */
    @PostMapping("/initial-reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse submitInitialReport(@Valid @RequestBody InitialReportRequest request) {
        return service.submitInitialReport(request);
    }

    /**
     * 提交随访。
     */
    @PostMapping("/cases/{caseNo}/follow-ups")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse submitFollowUp(@PathVariable String caseNo,
                                         @Valid @RequestBody FollowUpRequest request) {
        return service.submitFollowUp(caseNo, request);
    }

    /**
     * 申请关闭个例（幂等）。
     */
    @PostMapping("/cases/{caseNo}/close")
    public CloseCaseResponse closeCase(@PathVariable String caseNo) {
        return service.closeCase(caseNo);
    }

    /**
     * 创建监管递交批次（按 requestId 幂等，内容为提交时刻快照）。
     */
    @PostMapping("/submission-batches")
    @ResponseStatus(HttpStatus.CREATED)
    public SubmissionBatchResponse createSubmissionBatch(
            @Valid @RequestBody SubmissionBatchRequest request) {
        return service.createSubmissionBatch(request);
    }

    /**
     * 查询个例详情。
     */
    @GetMapping("/cases/{caseNo}")
    public CaseView getCase(@PathVariable String caseNo) {
        return service.getCase(caseNo);
    }

    /**
     * 查询递交批次。
     */
    @GetMapping("/submission-batches/{batchNo}")
    public SubmissionBatchResponse getBatch(@PathVariable String batchNo) {
        return service.getBatch(batchNo);
    }
}
