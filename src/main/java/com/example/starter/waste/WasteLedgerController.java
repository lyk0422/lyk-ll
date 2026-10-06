package com.example.starter.waste;

import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 垃圾分类收运台账接口：进场称重、回执闭环、异常核查结论、日结汇总。
 */
@RestController
@RequestMapping("/api")
public class WasteLedgerController {

    private final WeighingService weighingService;
    private final ReceiptService receiptService;
    private final ExceptionCaseService exceptionCaseService;
    private final SettlementService settlementService;

    public WasteLedgerController(WeighingService weighingService, ReceiptService receiptService,
            ExceptionCaseService exceptionCaseService, SettlementService settlementService) {
        this.weighingService = weighingService;
        this.receiptService = receiptService;
        this.exceptionCaseService = exceptionCaseService;
        this.settlementService = settlementService;
    }

    /**
     * 进场称重并登记联单。
     */
    @PostMapping("/weighings/entry")
    @ResponseStatus(HttpStatus.OK)
    public ManifestResponse entry(@Valid @RequestBody EntryWeighingRequest request) {
        return weighingService.registerEntry(request);
    }

    /**
     * 处理设施回执，闭环联单或转异常核查。
     */
    @PostMapping("/manifests/{manifestId}/receipts")
    @ResponseStatus(HttpStatus.OK)
    public ReceiptResponse receipt(@PathVariable long manifestId, @Valid @RequestBody ReceiptRequest request) {
        return receiptService.registerReceipt(manifestId, request);
    }

    /**
     * 登记异常核查结论（DEVICE_ERROR 设备误差 / LOADING_DIFF 装载差异）。
     */
    @PostMapping("/exceptions/{exceptionId}/conclusion")
    @ResponseStatus(HttpStatus.OK)
    public ExceptionCaseResponse conclude(@PathVariable long exceptionId,
            @Valid @RequestBody ConclusionRequest request) {
        return exceptionCaseService.conclude(exceptionId, request);
    }

    /**
     * 查询异常核查单。
     */
    @GetMapping("/exceptions/{exceptionId}")
    public ExceptionCaseResponse getException(@PathVariable long exceptionId) {
        return exceptionCaseService.get(exceptionId);
    }

    /**
     * 日结：按品类汇总当日进出量，date 缺省为业务时钟当日。
     */
    @GetMapping("/settlements/daily")
    public DailySettlementResponse daily(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return settlementService.daily(date);
    }
}
