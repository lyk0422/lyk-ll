package com.example.starter.web;

import com.example.starter.dto.CorrectRequest;
import com.example.starter.dto.DeclareRequest;
import com.example.starter.dto.IssueRequest;
import com.example.starter.dto.LateDocumentRequest;
import com.example.starter.dto.ServiceResult;
import com.example.starter.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 疫苗批签发资料审查与公示 REST 入口。
 */
@RestController
@RequestMapping("/api/batch-releases")
public class BatchReleaseController {

    private final ReviewService reviewService;

    public BatchReleaseController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 申报受理（requestId 幂等；缺项一次性告知补正）。 */
    @PostMapping("/declarations")
    public ResponseEntity<Object> declare(@Valid @RequestBody DeclareRequest request) {
        return toEntity(reviewService.declare(request));
    }

    /** 补正提交（恰第 5 个工作日受理，逾期按撤回）。 */
    @PostMapping("/{batchNo}/corrections")
    public ResponseEntity<Object> correct(@PathVariable String batchNo,
                                          @Valid @RequestBody CorrectRequest request) {
        return toEntity(reviewService.correct(batchNo, request));
    }

    /** 综合评定（关键项目全项合格则通过并启动公示，否则驳回并累计连续不合格）。 */
    @PostMapping("/{batchNo}/evaluation")
    public ResponseEntity<Object> evaluate(@PathVariable String batchNo) {
        return toEntity(reviewService.evaluate(batchNo));
    }

    /** 公示期内异议受理，转复核（与期满生效互斥，形成唯一终态）。 */
    @PostMapping("/{batchNo}/objections")
    public ResponseEntity<Object> objection(@PathVariable String batchNo) {
        return toEntity(reviewService.acceptObjection(batchNo));
    }

    /** 公示期满生效（UTC 日界，恰第 7 日期满）。 */
    @PostMapping("/{batchNo}/publicity-settlement")
    public ResponseEntity<Object> settle(@PathVariable String batchNo) {
        return toEntity(reviewService.settlePublicity(batchNo));
    }

    /** 签发归档（requestId 幂等；重复归档幂等成功）。 */
    @PostMapping("/{batchNo}/issues")
    public ResponseEntity<Object> issue(@PathVariable String batchNo,
                                        @Valid @RequestBody IssueRequest request) {
        return toEntity(reviewService.issue(batchNo, request));
    }

    /** 归档后迟到资料上报：只登记迟到事件。 */
    @PostMapping("/{batchNo}/late-documents")
    public ResponseEntity<Object> lateDocuments(@PathVariable String batchNo,
                                                @Valid @RequestBody LateDocumentRequest request) {
        return toEntity(reviewService.reportLateDocument(batchNo, request));
    }

    /** 查询审查全量视图（资料核对表、检验链、公示记录）。 */
    @GetMapping("/{batchNo}")
    public ResponseEntity<Object> get(@PathVariable String batchNo) {
        return ResponseEntity.ok(reviewService.getReview(batchNo));
    }

    /** 查询企业-品种连续不合格与暂停受理状态。 */
    @GetMapping("/enterprises/{enterpriseName}/products/{productName}/status")
    public ResponseEntity<Object> enterpriseStatus(@PathVariable String enterpriseName,
                                                   @PathVariable String productName) {
        return ResponseEntity.ok(reviewService.getEnterpriseStatus(enterpriseName, productName));
    }

    /** 查询事件流水（资料核对表、检验链、公示链可复算）。 */
    @GetMapping("/{batchNo}/events")
    public ResponseEntity<Object> events(@PathVariable String batchNo) {
        return ResponseEntity.ok(reviewService.listEvents(batchNo));
    }

    private static ResponseEntity<Object> toEntity(ServiceResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
