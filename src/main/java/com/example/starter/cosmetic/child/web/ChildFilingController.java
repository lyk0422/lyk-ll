package com.example.starter.cosmetic.child.web;

import com.example.starter.cosmetic.child.service.ChildFilingService;
import com.example.starter.cosmetic.child.web.dto.AgeRuleResponse;
import com.example.starter.cosmetic.child.web.dto.ChildChangeRequest;
import com.example.starter.cosmetic.child.web.dto.ChildCorrectionRequest;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResponse;
import com.example.starter.cosmetic.child.web.dto.ChildFilingResultResponse;
import com.example.starter.cosmetic.child.web.dto.ChildRevisionResponse;
import com.example.starter.cosmetic.child.web.dto.ChildSubmitRequest;
import com.example.starter.cosmetic.web.dto.ReviewDetailResponse;
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
 * 儿童化妆品分龄备案 HTTP 入口。
 *
 * <p>错误语义：参数非法 400、对象不存在 404、业务拒绝 422、同键异参 409。</p>
 */
@RestController
@RequestMapping("/api/child-filings")
public class ChildFilingController {

    private final ChildFilingService childFilingService;

    public ChildFilingController(ChildFilingService childFilingService) {
        this.childFilingService = childFilingService;
    }

    /** 儿童备案提交（同步分龄审查：通过受理并公示，不通过零生效且缺项一次列全）。 */
    @PostMapping
    public ResponseEntity<ChildFilingResultResponse> submit(
            @Valid @RequestBody ChildSubmitRequest request) {
        return ResponseEntity.ok(childFilingService.submit(request));
    }

    /** 材料补正：生成新修订并使旧修订待审批准失效。 */
    @PostMapping("/{filingNo}/corrections")
    public ResponseEntity<ChildFilingResultResponse> correct(
            @PathVariable String filingNo,
            @Valid @RequestBody ChildCorrectionRequest request) {
        return ResponseEntity.ok(childFilingService.correct(filingNo, request));
    }

    /** 备案变更（仅生效备案；儿童属性/年龄/标识适用范围变化必须实质变更）。 */
    @PostMapping("/{filingNo}/changes")
    public ResponseEntity<ChildFilingResultResponse> change(
            @PathVariable String filingNo,
            @Valid @RequestBody ChildChangeRequest request) {
        return ResponseEntity.ok(childFilingService.change(filingNo, request));
    }

    /** 查询儿童备案（含当前最新修订）。 */
    @GetMapping("/{filingNo}")
    public ChildFilingResponse getFiling(@PathVariable String filingNo) {
        return childFilingService.getFiling(filingNo);
    }

    /** 查询备案全部修订链。 */
    @GetMapping("/{filingNo}/revisions")
    public List<ChildRevisionResponse> revisions(@PathVariable String filingNo) {
        return childFilingService.getRevisions(filingNo);
    }

    /** 判定证据查询（revisionNo 可选），可复算分龄审查规则。 */
    @GetMapping("/{filingNo}/evidence")
    public List<ReviewDetailResponse> evidence(@PathVariable String filingNo,
                                               @RequestParam(required = false) Integer revisionNo) {
        return childFilingService.getEvidence(filingNo, revisionNo);
    }

    /** 查询当前启用的分龄规则（合成初始化内容）。 */
    @GetMapping("/age-rules")
    public AgeRuleResponse ageRules() {
        return childFilingService.getAgeRules();
    }

    /** 主动触发儿童备案公示期满自动生效扫描（atUtc 为空取当前时刻）。 */
    @PostMapping("/publicity-expiry")
    public Map<String, Integer> expire(@RequestParam(required = false) Instant atUtc) {
        return Map.of("effected", childFilingService.expireDuePublicities(atUtc));
    }
}
