package com.example.starter.cosmetic.child.web;

import com.example.starter.cosmetic.child.service.ChildFilingService;
import com.example.starter.cosmetic.child.web.dto.ChildChangeFilingRequest;
import com.example.starter.cosmetic.child.web.dto.ChildCorrectFilingRequest;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResponse;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResultResponse;
import com.example.starter.cosmetic.child.web.dto.ChildReviewDetailResponse;
import com.example.starter.cosmetic.child.web.dto.ChildRuleResponse;
import com.example.starter.cosmetic.child.web.dto.ChildSubmitFilingRequest;
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
 * 儿童化妆品分龄备案 HTTP 入口（与成人 /api/filings 相互独立，成人入口保持不变）。
 */
@RestController
@RequestMapping("/api/child-filings")
public class ChildFilingController {

    private final ChildFilingService childFilingService;

    public ChildFilingController(ChildFilingService childFilingService) {
        this.childFilingService = childFilingService;
    }

    /** 分龄合成规则初始化查询：返回版本、两档定义与各档成分限值。 */
    @GetMapping("/age-rule")
    public ChildRuleResponse rule() {
        return childFilingService.getRule();
    }

    /** 儿童备案首次提交（同步分龄形式审查：通过进公示，不通过驳回零生效）。 */
    @PostMapping
    public ResponseEntity<ChildFilingResultResponse> submit(
            @Valid @RequestBody ChildSubmitFilingRequest request) {
        return ResponseEntity.ok(childFilingService.submit(request));
    }

    /** 材料补正：同号新修订（version+1）并使旧待审修订失效；携带 expectedVersion。 */
    @PostMapping("/{filingNo}/corrections")
    public ResponseEntity<ChildFilingResultResponse> correct(
            @PathVariable String filingNo,
            @Valid @RequestBody ChildCorrectFilingRequest request) {
        return ResponseEntity.ok(childFilingService.correct(filingNo, request));
    }

    /** 备案变更（自动判定实质/简易）；年龄/儿童属性/标识范围改变必为实质变更。 */
    @PostMapping("/products/{productCode}/changes")
    public ResponseEntity<ChildFilingResultResponse> change(
            @PathVariable String productCode,
            @Valid @RequestBody ChildChangeFilingRequest request) {
        return ResponseEntity.ok(childFilingService.change(productCode, request));
    }

    /** 查询儿童备案最新修订。 */
    @GetMapping("/{filingNo}")
    public ChildFilingResponse getFiling(@PathVariable String filingNo) {
        return childFilingService.getFiling(filingNo);
    }

    /** 查询产品儿童备案历史修订链。 */
    @GetMapping("/products/{productCode}/history")
    public List<ChildFilingResponse> history(@PathVariable String productCode) {
        return childFilingService.getProductHistory(productCode);
    }

    /** 查询分龄审查判定证据（versionNo 可选）。 */
    @GetMapping("/{filingNo}/reviews")
    public List<ChildReviewDetailResponse> reviews(
            @PathVariable String filingNo,
            @RequestParam(required = false) Integer versionNo) {
        return childFilingService.getReviews(filingNo, versionNo);
    }

    /** 主动触发儿童公示期满自动生效扫描（atUtc 为空取当前时刻；仅最新修订可生效）。 */
    @PostMapping("/public-notice-expiry")
    public Map<String, Integer> expire(@RequestParam(required = false) Instant atUtc) {
        return Map.of("effected", childFilingService.expireDuePublicNotices(atUtc));
    }
}
