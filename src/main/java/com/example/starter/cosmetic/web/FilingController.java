package com.example.starter.cosmetic.web;

import com.example.starter.cosmetic.service.FilingService;
import com.example.starter.cosmetic.web.dto.ChangeFilingRequest;
import com.example.starter.cosmetic.web.dto.FilingResponse;
import com.example.starter.cosmetic.web.dto.FilingResultResponse;
import com.example.starter.cosmetic.web.dto.SubmitFilingRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 化妆品备案 HTTP 入口。
 */
@RestController
@RequestMapping("/api/filings")
public class FilingController {

    private final FilingService filingService;

    public FilingController(FilingService filingService) {
        this.filingService = filingService;
    }

    /** 备案提交（同步形式审查：通过即进入公示）。 */
    @PostMapping
    public ResponseEntity<FilingResultResponse> submit(@Valid @RequestBody SubmitFilingRequest request) {
        FilingResultResponse result = filingService.submit(request);
        return ResponseEntity.ok(result);
    }

    /** 备案变更（服务端自动判定实质/简易）。 */
    @PostMapping("/products/{productCode}/changes")
    public ResponseEntity<FilingResultResponse> change(@PathVariable String productCode,
                                                       @Valid @RequestBody ChangeFilingRequest request) {
        return ResponseEntity.ok(filingService.change(productCode, request));
    }

    /** 公示期内受理异议（可显式给异议受理时刻 UTC，便于复算竞态）。 */
    @PostMapping("/{filingNo}/objections")
    public ResponseEntity<FilingResultResponse> objection(@PathVariable String filingNo,
                                                          @RequestParam(required = false) String reason,
                                                          @RequestParam(required = false) Instant atUtc) {
        return ResponseEntity.ok(filingService.acceptObjection(filingNo, atUtc, reason));
    }

    /** 异议审查结论：upheld=true 成立则终态驳回；false 回到公示。 */
    @PostMapping("/{filingNo}/objections/resolution")
    public ResponseEntity<FilingResultResponse> resolveObjection(@PathVariable String filingNo,
                                                                 @RequestParam boolean upheld,
                                                                 @RequestParam(required = false) String conclusion) {
        return ResponseEntity.ok(filingService.resolveObjection(filingNo, upheld, conclusion));
    }

    /** 注销（终态，重复注销幂等成功）。 */
    @PostMapping("/{filingNo}/withdrawal")
    public ResponseEntity<FilingResultResponse> withdraw(@PathVariable String filingNo,
                                                         @RequestParam String requestId,
                                                         @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(filingService.withdraw(filingNo, requestId, reason));
    }

    /** 查询备案最新版本（历史版本见产品变更链）。 */
    @GetMapping("/{filingNo}")
    public FilingResponse getFiling(@PathVariable String filingNo) {
        return filingService.getFiling(filingNo);
    }

    /** 查询产品历史备案链（审查明细可经版本复算）。 */
    @GetMapping("/products/{productCode}/history")
    public List<FilingResponse> history(@PathVariable String productCode) {
        return filingService.getProductHistory(productCode);
    }

    /** 查询审查明细（versionNo 可选），用于复算形式/异议审查规则与期限口径。 */
    @GetMapping("/{filingNo}/reviews")
    public List<com.example.starter.cosmetic.web.dto.ReviewDetailResponse> reviews(
            @PathVariable String filingNo,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer versionNo) {
        return filingService.getReviews(filingNo, versionNo);
    }

    /** 主动触发公示期满自动生效扫描（atUtc 为空取当前时刻）。 */
    @PostMapping("/public-notice-expiry")
    public Map<String, Integer> expire(@RequestParam(required = false) Instant atUtc) {
        return Map.of("effected", filingService.expireDuePublicNotices(atUtc));
    }
}
