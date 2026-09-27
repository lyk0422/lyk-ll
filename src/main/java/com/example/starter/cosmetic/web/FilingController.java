package com.example.starter.cosmetic.web;

import com.example.starter.cosmetic.service.FilingService;
import com.example.starter.cosmetic.web.dto.AgeRuleResponse;
import com.example.starter.cosmetic.web.dto.ChangeFilingRequest;
import com.example.starter.cosmetic.web.dto.ChildEvidenceResponse;
import com.example.starter.cosmetic.web.dto.CorrectionFilingRequest;
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

    /** 备案变更（服务端自动判定实质/简易；儿童属性、年龄或标识适用范围改变一律实质变更）。 */
    @PostMapping("/products/{productCode}/changes")
    public ResponseEntity<FilingResultResponse> change(@PathVariable String productCode,
                                                       @Valid @RequestBody ChangeFilingRequest request) {
        return ResponseEntity.ok(filingService.change(productCode, request));
    }

    /** 儿童备案材料补正：生成新修订并使旧修订待审批准失效，公示只能使用最新修订。 */
    @PostMapping("/{filingNo}/corrections")
    public ResponseEntity<FilingResultResponse> correct(@PathVariable String filingNo,
                                                        @Valid @RequestBody CorrectionFilingRequest request) {
        return ResponseEntity.ok(filingService.correct(filingNo, request));
    }

    /** 儿童备案判定证据查询：受理冻结的报告清单、分龄规则版本、标识文本摘要及审查明细。 */
    @GetMapping("/{filingNo}/child-evidence")
    public ChildEvidenceResponse childEvidence(@PathVariable String filingNo,
                                               @RequestParam(required = false) Integer versionNo) {
        return filingService.getChildEvidence(filingNo, versionNo);
    }

    /** 儿童分龄规则查询（合成初始化结果）。 */
    @GetMapping("/age-rule")
    public AgeRuleResponse ageRule() {
        return filingService.getAgeRule();
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
