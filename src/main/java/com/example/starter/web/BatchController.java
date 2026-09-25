package com.example.starter.web;

import com.example.starter.service.BatchService;
import com.example.starter.web.dto.BatchView;
import com.example.starter.web.dto.DeclareRequest;
import com.example.starter.web.dto.DeclareResponse;
import com.example.starter.web.dto.InspectionRequest;
import com.example.starter.web.dto.InspectionResponse;
import com.example.starter.web.dto.JudgmentRequest;
import com.example.starter.web.dto.JudgmentResponse;
import com.example.starter.web.dto.PreQuarantineRequest;
import com.example.starter.web.dto.PreQuarantineResponse;
import com.example.starter.web.dto.ReceiptRequest;
import com.example.starter.web.dto.ReceiptResponse;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.ReleaseResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 屠宰检疫接口：申报→宰前检疫→同步检验→判定分流→回执登记→出厂核销。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchService service;

    public BatchController(BatchService service) {
        this.service = service;
    }

    /** 宰前申报（幂等）。 */
    @PostMapping("/declare")
    public DeclareResponse declare(@Valid @RequestBody DeclareRequest request) {
        return service.declare(request);
    }

    /** 宰前检疫（幂等）：自申报起 6 小时内（UTC）有效。 */
    @PostMapping("/{batchNo}/pre-quarantine")
    public PreQuarantineResponse preQuarantine(@PathVariable String batchNo,
                                               @Valid @RequestBody PreQuarantineRequest request) {
        return service.preQuarantine(batchNo, request);
    }

    /** 同步检验（幂等）：按头只编号一一对应。 */
    @PostMapping("/{batchNo}/inspections")
    public InspectionResponse inspect(@PathVariable String batchNo,
                                      @Valid @RequestBody InspectionRequest request) {
        return service.inspect(batchNo, request);
    }

    /** 判定分流（幂等）：合格/有条件食用/化制/销毁，判定与库存标识同事务。 */
    @PostMapping("/{batchNo}/judgments")
    public JudgmentResponse judge(@PathVariable String batchNo,
                                  @Valid @RequestBody JudgmentRequest request) {
        return service.judge(batchNo, request);
    }

    /** 无害化处理回执登记（幂等）：化制/销毁闭环凭证。 */
    @PostMapping("/{batchNo}/receipts")
    public ReceiptResponse registerReceipt(@PathVariable String batchNo,
                                           @Valid @RequestBody ReceiptRequest request) {
        return service.registerReceipt(batchNo, request);
    }

    /** 出厂核销（幂等）：回执勾稽恰等受理；核销为终态，重复核销返回幂等成功。 */
    @PostMapping("/{batchNo}/release")
    public ReleaseResponse release(@PathVariable String batchNo,
                                   @Valid @RequestBody ReleaseRequest request) {
        return service.release(batchNo, request);
    }

    /** 批次全量视图：头只台账、判定明细、回执勾稽与迟到事件。 */
    @GetMapping("/{batchNo}")
    public BatchView view(@PathVariable String batchNo) {
        return service.getView(batchNo);
    }
}
