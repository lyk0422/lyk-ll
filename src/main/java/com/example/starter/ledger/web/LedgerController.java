package com.example.starter.ledger.web;

import com.example.starter.ledger.service.LedgerService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

/**
 * 换电资产台账 HTTP 接口。
 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** 电池建档。 */
    @PostMapping("/batteries")
    public ResponseEntity<BatteryResponse> register(@Valid @RequestBody RegisterBatteryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ledgerService.registerBattery(request));
    }

    /** 回站入站。 */
    @PostMapping("/inbound")
    public ResponseEntity<Envelope<BatteryResponse>> inbound(@Valid @RequestBody InboundRequest request) {
        OperationResult<BatteryResponse> result = ledgerService.inbound(request);
        return ResponseEntity.ok(new Envelope<>(result.getData(), result.isReplayed()));
    }

    /** 提交检测结论（双阈值门槛放行）。 */
    @PostMapping("/inspections")
    public ResponseEntity<Envelope<BatteryResponse>> inspect(@Valid @RequestBody InspectionRequest request) {
        OperationResult<BatteryResponse> result = ledgerService.inspect(request);
        return ResponseEntity.ok(new Envelope<>(result.getData(), result.isReplayed()));
    }

    /** 换出并绑定车辆。 */
    @PostMapping("/swap-out")
    public ResponseEntity<Envelope<BatteryResponse>> swapOut(@Valid @RequestBody SwapOutRequest request) {
        OperationResult<BatteryResponse> result = ledgerService.swapOut(request);
        return ResponseEntity.ok(new Envelope<>(result.getData(), result.isReplayed()));
    }

    /** 站间调拨调出。 */
    @PostMapping("/transfers/dispatch")
    public ResponseEntity<Envelope<TransferResponse>> dispatch(@Valid @RequestBody TransferDispatchRequest request) {
        OperationResult<TransferResponse> result = ledgerService.dispatchTransfer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new Envelope<>(result.getData(), result.isReplayed()));
    }

    /** 到站签收。 */
    @PostMapping("/transfers/receive")
    public ResponseEntity<Envelope<TransferResponse>> receive(@Valid @RequestBody TransferReceiveRequest request) {
        OperationResult<TransferResponse> result = ledgerService.receiveTransfer(request);
        return ResponseEntity.ok(new Envelope<>(result.getData(), result.isReplayed()));
    }

    /** 调拨异常人工核销。 */
    @PostMapping("/transfers/write-off")
    public ResponseEntity<Envelope<TransferResponse>> writeOff(@Valid @RequestBody TransferWriteOffRequest request) {
        OperationResult<TransferResponse> result = ledgerService.writeOffTransfer(request);
        return ResponseEntity.ok(new Envelope<>(result.getData(), result.isReplayed()));
    }

    /** 查询电池资产。 */
    @GetMapping("/batteries/{code}")
    public BatteryResponse battery(@PathVariable String code) {
        return ledgerService.getBattery(code);
    }

    /** 查询电池事件链。 */
    @GetMapping("/batteries/{code}/events")
    public List<EventResponse> events(@PathVariable String code) {
        return ledgerService.getEvents(code);
    }

    /** 事件链验真。 */
    @GetMapping("/batteries/{code}/verify")
    public LedgerService.ChainVerificationView verify(@PathVariable String code) {
        return ledgerService.verifyChain(code);
    }

    /** 幂等重放标记信封；replayed=false 表示首次处理。 */
    public record Envelope<T>(T data, boolean replayed) {
    }
}
