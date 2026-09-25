package com.example.starter.feed.web;

import com.example.starter.feed.dto.FormulaApproveRequest;
import com.example.starter.feed.dto.FormulaSubmitRequest;
import com.example.starter.feed.dto.FormulaSubmitResponse;
import com.example.starter.feed.dto.FormulaVersionTrailResponse;
import com.example.starter.feed.service.FormulaService;
import com.example.starter.feed.service.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配方接口：提交（合规校验）、批准、版本轨迹。
 */
@RestController
@RequestMapping("/api/formulas")
public class FormulaController {

    private final FormulaService formulaService;

    public FormulaController(FormulaService formulaService) {
        this.formulaService = formulaService;
    }

    /** 提交配方并合规校验；拒绝（超上限/禁忌/种类错配）返回 422 并携带原因。 */
    @PostMapping("/submit")
    public ResponseEntity<FormulaSubmitResponse> submit(@Valid @RequestBody FormulaSubmitRequest request) {
        IdempotencyService.Result<FormulaSubmitResponse> result = formulaService.submit(request);
        return ResponseEntity.status(result.httpStatus()).body(result.response());
    }

    /** 批准合规通过的版本；批准即冻结。 */
    @PostMapping("/approve")
    public FormulaVersionTrailResponse.VersionView approve(@Valid @RequestBody FormulaApproveRequest request) {
        return formulaService.approve(request);
    }

    /** 版本轨迹复算视图。 */
    @GetMapping("/{formulaCode}/versions")
    public FormulaVersionTrailResponse versions(@PathVariable String formulaCode) {
        return formulaService.trail(formulaCode);
    }
}
