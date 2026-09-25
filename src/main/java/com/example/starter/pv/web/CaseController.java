package com.example.starter.pv.web;

import java.util.List;

import com.example.starter.pv.service.CaseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 药物警戒个例管理接口。
 */
@RestController
@RequestMapping("/api/pv")
public class CaseController {

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    /** 初报；重复活动个例按随访并入。 */
    @PostMapping("/initial-reports")
    @ResponseStatus(HttpStatus.CREATED)
    public InitialReportResponse submitInitial(@Valid @RequestBody InitialReportRequest request) {
        return caseService.submitInitial(request);
    }

    /** 随访信息提交（幂等）。 */
    @PostMapping("/cases/{caseNo}/follow-ups")
    @ResponseStatus(HttpStatus.CREATED)
    public FollowUpResponse submitFollowUp(@PathVariable String caseNo,
                                           @Valid @RequestBody FollowUpRequest request) {
        return caseService.submitFollowUp(caseNo, request);
    }

    /** 申请关闭个例（终态，重复关闭幂等成功）。 */
    @PostMapping("/cases/{caseNo}/close")
    public CloseResponse close(@PathVariable String caseNo, @RequestBody(required = false) CloseRequest request) {
        return caseService.close(caseNo, request == null ? new CloseRequest(null) : request);
    }

    /** 提交监管，生成唯一批次号，内容为提交时刻快照。 */
    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchResponse submitBatch(@Valid @RequestBody SubmitBatchRequest request) {
        return caseService.submitBatch(request);
    }

    /** 查询个例详情。 */
    @GetMapping("/cases/{caseNo}")
    public CaseView getCase(@PathVariable String caseNo) {
        return caseService.getCase(caseNo);
    }

    /** 查询全部个例（按编号升序）。 */
    @GetMapping("/cases")
    public List<CaseView> listCases() {
        return caseService.listCases();
    }

    /** 查询批次及其固定快照。 */
    @GetMapping("/batches/{batchNo}")
    public BatchResponse getBatch(@PathVariable String batchNo) {
        return caseService.getBatch(batchNo);
    }
}
