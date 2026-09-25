package com.example.starter.slaughter.web;

import com.example.starter.slaughter.dto.CheckoutRequest;
import com.example.starter.slaughter.dto.CheckoutResponse;
import com.example.starter.slaughter.dto.DeclareRequest;
import com.example.starter.slaughter.dto.DeclareResponse;
import com.example.starter.slaughter.dto.InspectionCompleteRequest;
import com.example.starter.slaughter.dto.InspectionRegisterRequest;
import com.example.starter.slaughter.dto.InspectionResponse;
import com.example.starter.slaughter.dto.JudgmentRequest;
import com.example.starter.slaughter.dto.JudgmentResponse;
import com.example.starter.slaughter.dto.PreQuarantineRequest;
import com.example.starter.slaughter.dto.PreQuarantineResponse;
import com.example.starter.slaughter.dto.ReceiptRequest;
import com.example.starter.slaughter.dto.ReceiptResponse;
import com.example.starter.slaughter.dto.ReconciliationResponse;
import com.example.starter.slaughter.service.ServiceOutcome;
import com.example.starter.slaughter.service.SlaughterService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生猪屠宰同步检疫与无害化处理 API。
 * 流转：宰前申报 → 宰前检疫 → 同步检验 → 判定分流 → 出厂核销。
 * 同键同参重放时响应头 {@code X-Idempotent-Replayed: true}，响应体与首次一致。
 */
@RestController
@RequestMapping("/api/slaughter/batches")
public class SlaughterController {

    static final String REPLAY_HEADER = "X-Idempotent-Replayed";

    private final SlaughterService service;

    public SlaughterController(SlaughterService service) {
        this.service = service;
    }

    /** 宰前申报；恰满 6 小时未屠宰时对同一批次重新申报亦走此入口。 */
    @PostMapping
    public ResponseEntity<DeclareResponse> declare(@Valid @RequestBody DeclareRequest request) {
        ServiceOutcome<DeclareResponse> outcome = service.declare(request);
        return ResponseEntity.status(outcome.httpStatus())
                .header(REPLAY_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.data());
    }

    /** 宰前检疫登记。 */
    @PostMapping("/{batchNo}/pre-quarantine")
    public ResponseEntity<PreQuarantineResponse> preQuarantine(@PathVariable String batchNo,
                                                               @Valid @RequestBody PreQuarantineRequest request) {
        ServiceOutcome<PreQuarantineResponse> outcome = service.preQuarantine(batchNo, request);
        return ResponseEntity.status(outcome.httpStatus())
                .header(REPLAY_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.data());
    }

    /** 同步检验按头只编号登记（首次登记开启该批次唯一检验链）。 */
    @PostMapping("/{batchNo}/inspections")
    public ResponseEntity<InspectionResponse> registerInspection(
            @PathVariable String batchNo,
            @Valid @RequestBody InspectionRegisterRequest request) {
        ServiceOutcome<InspectionResponse> outcome = service.registerInspection(batchNo, request);
        return ResponseEntity.status(outcome.httpStatus())
                .header(REPLAY_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.data());
    }

    /** 检验链收口：漏检头只整批暂停流转。 */
    @PostMapping("/{batchNo}/inspections/complete")
    public ResponseEntity<InspectionResponse> completeInspection(
            @PathVariable String batchNo,
            @Valid @RequestBody InspectionCompleteRequest request) {
        ServiceOutcome<InspectionResponse> outcome = service.completeInspection(batchNo, request);
        return ResponseEntity.status(outcome.httpStatus())
                .header(REPLAY_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.data());
    }

    /** 判定分流：合格/有条件食用/化制/销毁，与库存标识同一事务完成。 */
    @PostMapping("/{batchNo}/judgments")
    public ResponseEntity<JudgmentResponse> judge(@PathVariable String batchNo,
                                                  @Valid @RequestBody JudgmentRequest request) {
        ServiceOutcome<JudgmentResponse> outcome = service.judge(batchNo, request);
        return ResponseEntity.status(outcome.httpStatus())
                .header(REPLAY_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.data());
    }

    /** 无害化处理回执登记：回执头只数与判定数勾稽，恰等受理，不等拒绝出厂。 */
    @PostMapping("/{batchNo}/receipts")
    public ResponseEntity<ReceiptResponse> registerReceipt(@PathVariable String batchNo,
                                                           @Valid @RequestBody ReceiptRequest request) {
        ServiceOutcome<ReceiptResponse> outcome = service.registerReceipt(batchNo, request);
        return ResponseEntity.status(outcome.httpStatus())
                .header(REPLAY_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.data());
    }

    /** 出厂核销（终态）；重复核销幂等成功。 */
    @PostMapping("/{batchNo}/checkout")
    public ResponseEntity<CheckoutResponse> checkout(@PathVariable String batchNo,
                                                     @Valid @RequestBody CheckoutRequest request) {
        ServiceOutcome<CheckoutResponse> outcome = service.checkout(batchNo, request);
        return ResponseEntity.status(outcome.httpStatus())
                .header(REPLAY_HEADER, String.valueOf(outcome.replayed()))
                .body(outcome.data());
    }

    /** 头只台账、判定明细与回执勾稽实时复算。 */
    @GetMapping("/{batchNo}/reconciliation")
    public ReconciliationResponse reconcile(@PathVariable String batchNo) {
        return service.reconcile(batchNo);
    }
}
