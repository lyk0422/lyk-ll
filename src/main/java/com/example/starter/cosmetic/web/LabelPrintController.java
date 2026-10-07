package com.example.starter.cosmetic.web;

import com.example.starter.cosmetic.service.LabelPrintService;
import com.example.starter.cosmetic.web.dto.DestroyLabelsRequest;
import com.example.starter.cosmetic.web.dto.DestroyResponse;
import com.example.starter.cosmetic.web.dto.DrawLabelsRequest;
import com.example.starter.cosmetic.web.dto.DrawRecordResponse;
import com.example.starter.cosmetic.web.dto.DrawResponse;
import com.example.starter.cosmetic.web.dto.ExpectedDigestResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResponse;
import com.example.starter.cosmetic.web.dto.PrintRunResultResponse;
import com.example.starter.cosmetic.web.dto.RegisterPrintRunRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 包装标签印次台账 HTTP 入口。
 */
@RestController
@RequestMapping("/api/label-print-runs")
public class LabelPrintController {

    private final LabelPrintService labelPrintService;

    public LabelPrintController(LabelPrintService labelPrintService) {
        this.labelPrintService = labelPrintService;
    }

    /** 登记印次：绑定产品当前有效备案的具体修订，核对标签摘要（儿童逐档）。 */
    @PostMapping
    public ResponseEntity<PrintRunResultResponse> register(
            @Valid @RequestBody RegisterPrintRunRequest request) {
        return ResponseEntity.ok(labelPrintService.register(request));
    }

    /** 领用标签：同产品包装批号 + 正整数张数，不能超未领余额，恰领完允许。 */
    @PostMapping("/{printRunNo}/draws")
    public ResponseEntity<DrawResponse> draw(@PathVariable String printRunNo,
                                             @Valid @RequestBody DrawLabelsRequest request) {
        return ResponseEntity.ok(labelPrintService.draw(printRunNo, request));
    }

    /** 登记销毁未领标签：只减少未领余额并留证，已领部分不能销毁退回。 */
    @PostMapping("/{printRunNo}/destroys")
    public ResponseEntity<DestroyResponse> destroy(@PathVariable String printRunNo,
                                                   @Valid @RequestBody DestroyLabelsRequest request) {
        return ResponseEntity.ok(labelPrintService.destroy(printRunNo, request));
    }

    /** 查询印次台账：核对 印刷总数=已领+已毁+剩余，并区分禁用剩余。 */
    @GetMapping("/{printRunNo}")
    public PrintRunResponse getPrintRun(@PathVariable String printRunNo) {
        return labelPrintService.getPrintRun(printRunNo);
    }

    /** 查询印次全部领用凭据（每包装批可多次领用，逐笔可追溯）。 */
    @GetMapping("/{printRunNo}/draws")
    public List<DrawRecordResponse> listDraws(@PathVariable String printRunNo) {
        return labelPrintService.listDraws(printRunNo);
    }

    /** 查询产品当前有效备案的标签摘要期望（登记印次前核对口径）。 */
    @GetMapping("/expected-digest")
    public ExpectedDigestResponse expectedDigest(@RequestParam String productCode) {
        return labelPrintService.expectedDigest(productCode);
    }
}
