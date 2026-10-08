package com.example.starter.web;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.ReceiptService;

/**
 * 处理设施回执闭环入口。
 */
@RestController
@RequestMapping("/api/manifests")
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    /**
     * 回执请求体。netKg 为处理设施复称净重，单位 kg。
     * facilityCode 与 destinationVersion 为受控回执的去向声称：已改投联单必填且须同时提供，
     * 恰为当前版本且设施匹配才入账，否则仅存迟到证据；未改投联单可省略（旧式调用）。
     */
    public record ReceiptRequest(
            @NotBlank String requestId,
            @NotNull @Positive BigDecimal netKg,
            String facilityCode,
            @Positive Integer destinationVersion) {
    }

    @PostMapping("/{manifestId}/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptService.ReceiptResult registerReceipt(@PathVariable long manifestId,
                                                        @Valid @RequestBody ReceiptRequest request) {
        return receiptService.registerReceipt(manifestId, request.requestId(), request.netKg(),
                request.facilityCode(), request.destinationVersion());
    }
}
