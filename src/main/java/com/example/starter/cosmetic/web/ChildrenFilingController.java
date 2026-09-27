package com.example.starter.cosmetic.web;

import com.example.starter.cosmetic.service.ChildrenFilingService;
import com.example.starter.cosmetic.web.dto.ChangeChildrenFilingRequest;
import com.example.starter.cosmetic.web.dto.ChildrenEvidenceResponse;
import com.example.starter.cosmetic.web.dto.ChildrenFilingResponse;
import com.example.starter.cosmetic.web.dto.ChildrenFilingResultResponse;
import com.example.starter.cosmetic.web.dto.CorrectChildrenFilingRequest;
import com.example.starter.cosmetic.web.dto.SubmitChildrenFilingRequest;
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
 * 儿童化妆品备案 HTTP 入口：分龄资料审查、材料补正、变更与判定证据查询。
 */
@RestController
@RequestMapping("/api/children-filings")
public class ChildrenFilingController {

    private final ChildrenFilingService childrenFilingService;

    public ChildrenFilingController(ChildrenFilingService childrenFilingService) {
        this.childrenFilingService = childrenFilingService;
    }

    /** 儿童备案提交（同步分龄审查：通过受理并进入公示，缺项一次列全 422）。 */
    @PostMapping
    public ResponseEntity<ChildrenFilingResultResponse> submit(
            @Valid @RequestBody SubmitChildrenFilingRequest request) {
        return ResponseEntity.ok(childrenFilingService.submit(request));
    }

    /** 材料补正：生成新修订并使旧修订待审批准失效，公示只使用最新修订。 */
    @PostMapping("/{filingNo}/corrections")
    public ResponseEntity<ChildrenFilingResultResponse> correct(
            @PathVariable String filingNo,
            @Valid @RequestBody CorrectChildrenFilingRequest request) {
        return ResponseEntity.ok(childrenFilingService.correct(filingNo, request));
    }

    /** 儿童备案变更（儿童属性/年龄/标识适用范围改变一律实质变更）。 */
    @PostMapping("/products/{productCode}/changes")
    public ResponseEntity<ChildrenFilingResultResponse> change(
            @PathVariable String productCode,
            @Valid @RequestBody ChangeChildrenFilingRequest request) {
        return ResponseEntity.ok(childrenFilingService.change(productCode, request));
    }

    /** 查询儿童备案最新修订。 */
    @GetMapping("/{filingNo}")
    public ChildrenFilingResponse getFiling(@PathVariable String filingNo) {
        return childrenFilingService.getFiling(filingNo);
    }

    /** 查询产品儿童备案历史链。 */
    @GetMapping("/products/{productCode}/history")
    public List<ChildrenFilingResponse> history(@PathVariable String productCode) {
        return childrenFilingService.getProductHistory(productCode);
    }

    /** 判定证据查询：受理冻结的报告清单、分龄规则版本、标识文本摘要及审查明细。 */
    @GetMapping("/{filingNo}/evidence")
    public ChildrenEvidenceResponse evidence(@PathVariable String filingNo) {
        return childrenFilingService.getEvidence(filingNo);
    }

    /** 主动触发儿童备案公示期满自动生效扫描（atUtc 为空取当前时刻）。 */
    @PostMapping("/public-notice-expiry")
    public Map<String, Integer> expire(@RequestParam(required = false) Instant atUtc) {
        return Map.of("effected", childrenFilingService.expireDuePublicNotices(atUtc));
    }
}
