package com.example.starter.battery.web;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.battery.BatteryLedgerService;
import com.example.starter.battery.web.Requests.InboundRequest;
import com.example.starter.battery.web.Requests.InspectionRequest;
import com.example.starter.battery.web.Requests.RegisterBatteryRequest;
import com.example.starter.battery.web.Requests.SignRequest;
import com.example.starter.battery.web.Requests.SwapOutRequest;
import com.example.starter.battery.web.Requests.TransferRequest;
import com.example.starter.battery.web.Responses.BatteryResponse;
import com.example.starter.battery.web.Responses.ChainResponse;
import com.example.starter.battery.web.Responses.EventResponse;
import com.example.starter.battery.web.Responses.InboundResponse;
import com.example.starter.battery.web.Responses.InspectionResponse;
import com.example.starter.battery.web.Responses.SwapOutResponse;
import com.example.starter.battery.web.Responses.TransferResponse;
import com.example.starter.battery.web.Responses.VerifyResponse;

/**
 * 换电资产台账 REST API。
 */
@RestController
@RequestMapping("/api")
public class BatteryLedgerController {

    private final BatteryLedgerService service;

    public BatteryLedgerController(BatteryLedgerService service) {
        this.service = service;
    }

    /** 建档：以唯一编码建立电池台账。 */
    @PostMapping("/batteries")
    @ResponseStatus(HttpStatus.CREATED)
    public BatteryResponse register(@Valid @RequestBody RegisterBatteryRequest request) {
        return service.register(request);
    }

    /** 入站：回站电池校验历史链一致后入站待检（幂等）。 */
    @PostMapping("/batteries/inbound")
    public InboundResponse inbound(@Valid @RequestBody InboundRequest request) {
        return service.inbound(request);
    }

    /** 检测：双阈值判定，达标上架，否则充电/待修（幂等，复检走新批次）。 */
    @PostMapping("/batteries/inspection")
    public InspectionResponse inspect(@Valid @RequestBody InspectionRequest request) {
        return service.inspect(request);
    }

    /** 换出：扣减站点库存并绑定车辆（幂等，并发唯一成功）。 */
    @PostMapping("/batteries/swap-out")
    public SwapOutResponse swapOut(@Valid @RequestBody SwapOutRequest request) {
        return service.swapOut(request);
    }

    /** 查询电池台账。 */
    @GetMapping("/batteries/{code}")
    public BatteryResponse getBattery(@PathVariable String code) {
        return service.getBattery(code);
    }

    /** 查询稳定有序的事件链。 */
    @GetMapping("/batteries/{code}/events")
    public ChainResponse events(@PathVariable String code) {
        List<EventResponse> events = service.getEvents(code).stream()
                .map(EventResponse::from)
                .toList();
        return new ChainResponse(code, events);
    }

    /** 事件链验真。 */
    @GetMapping("/batteries/{code}/verify")
    public VerifyResponse verify(@PathVariable String code) {
        return service.verify(code);
    }

    /** 站间调拨发起：调出方发起（幂等）。 */
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse initiateTransfer(@Valid @RequestBody TransferRequest request) {
        return service.initiateTransfer(request);
    }

    /** 到站签收：调入方签收（幂等）。 */
    @PostMapping("/transfers/{transferId}/sign")
    public TransferResponse sign(@PathVariable long transferId,
                                 @Valid @RequestBody SignRequest request) {
        return service.signTransfer(transferId, request.requestId());
    }

    /** 调拨异常人工核销（仅一次）。 */
    @PostMapping("/transfers/{transferId}/write-off")
    public TransferResponse writeOff(@PathVariable long transferId) {
        return service.writeOffTransfer(transferId);
    }

    /** 查询调拨单（超过 24 小时在途会自动转异常）。 */
    @GetMapping("/transfers/{transferId}")
    public TransferResponse getTransfer(@PathVariable long transferId) {
        return service.getTransfer(transferId);
    }

    /** 主动扫描在途超时调拨单并转异常。 */
    @PostMapping("/maintenance/refresh-overdue")
    public Map<String, Object> refreshOverdue() {
        int count = service.refreshOverdueTransfers();
        return Map.of("exceptionCount", count);
    }
}
