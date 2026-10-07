package com.example.starter.cosmetic.web;

import com.example.starter.cosmetic.service.LabelPrintService;
import com.example.starter.cosmetic.web.dto.DestroyLabelsRequest;
import com.example.starter.cosmetic.web.dto.IssueLabelsRequest;
import com.example.starter.cosmetic.web.dto.LabelDestructionResponse;
import com.example.starter.cosmetic.web.dto.LabelDestructionResultResponse;
import com.example.starter.cosmetic.web.dto.LabelIssueResponse;
import com.example.starter.cosmetic.web.dto.LabelIssueResultResponse;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 包装标签印次台账 HTTP 入口。
 *
 * <p>印次绑定已生效备案的具体修订；备案实质变更生效或注销时旧印次剩余标签
 * 原子禁用，已领凭据仅追加风险关联。</p>
 */
@RestController
@RequestMapping("/api/label-print-runs")
public class LabelPrintController {

    private final LabelPrintService labelPrintService;

    public LabelPrintController(LabelPrintService labelPrintService) {
        this.labelPrintService = labelPrintService;
    }

    /** 印次登记：引用当前有效备案与修订号，冻结标签文本摘要（儿童逐档一致）。 */
    @PostMapping
    public ResponseEntity<PrintRunResultResponse> register(
            @Valid @RequestBody RegisterPrintRunRequest request) {
        return ResponseEntity.ok(labelPrintService.register(request));
    }

    /** 标签领用：不超未领余额，恰领完允许；首次包装批登记所属产品，跨产品借用拒绝。 */
    @PostMapping("/{printRunNo}/issues")
    public ResponseEntity<LabelIssueResultResponse> issue(
            @PathVariable String printRunNo,
            @Valid @RequestBody IssueLabelsRequest request) {
        return ResponseEntity.ok(labelPrintService.issue(printRunNo, request));
    }

    /** 未领标签销毁登记：仅未领部分可销毁，留证；禁用印次不允许复活。 */
    @PostMapping("/{printRunNo}/destructions")
    public ResponseEntity<LabelDestructionResultResponse> destroy(
            @PathVariable String printRunNo,
            @Valid @RequestBody DestroyLabelsRequest request) {
        return ResponseEntity.ok(labelPrintService.destroy(printRunNo, request));
    }

    /** 印次台账查询：可核对 印刷总数=已领+已毁+剩余，并区分禁用剩余。 */
    @GetMapping("/{printRunNo}")
    public PrintRunResponse getPrintRun(@PathVariable String printRunNo) {
        return labelPrintService.getPrintRun(printRunNo);
    }

    /** 印次的全部领用凭据（可追溯）。 */
    @GetMapping("/{printRunNo}/issues")
    public List<LabelIssueResponse> listIssues(@PathVariable String printRunNo) {
        return labelPrintService.listIssues(printRunNo);
    }

    /** 印次的全部销毁登记。 */
    @GetMapping("/{printRunNo}/destructions")
    public List<LabelDestructionResponse> listDestructions(@PathVariable String printRunNo) {
        return labelPrintService.listDestructions(printRunNo);
    }

    /** 包装批的全部领用凭据（跨印次追溯）。 */
    @GetMapping("/packaging-batches/{batchNo}/issues")
    public List<LabelIssueResponse> listBatchIssues(@PathVariable String batchNo) {
        return labelPrintService.listBatchIssues(batchNo);
    }
}
