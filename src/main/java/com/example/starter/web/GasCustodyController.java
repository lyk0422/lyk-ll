package com.example.starter.web;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.GasCustodyService;
import com.example.starter.web.dto.AdjustmentRequest;
import com.example.starter.web.dto.AssessmentResponse;
import com.example.starter.web.dto.ConfirmRequest;
import com.example.starter.web.dto.DisputeRequest;
import com.example.starter.web.dto.NominationRequest;
import com.example.starter.web.dto.NominationResponse;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.ReadingResponse;
import com.example.starter.web.dto.StationRequest;
import com.example.starter.web.dto.StationResponse;
import com.example.starter.web.dto.VoucherResponse;
import com.example.starter.web.dto.VoucherSubmitRequest;

/**
 * 天然气交接计量 REST 入口。
 */
@RestController
@RequestMapping("/api/gas")
public class GasCustodyController {

    private final GasCustodyService service;

    public GasCustodyController(GasCustodyService service) {
        this.service = service;
    }

    /** 创建交接站。 */
    @PostMapping("/stations")
    public ResponseEntity<StationResponse> createStation(@Valid @RequestBody StationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createStation(request));
    }

    /** 登记日指定气量。 */
    @PostMapping("/nominations")
    public ResponseEntity<NominationResponse> saveNomination(
            @Valid @RequestBody NominationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveNomination(request));
    }

    /** 上报计量读数。 */
    @PostMapping("/readings")
    public ResponseEntity<ReadingResponse> uploadReading(@Valid @RequestBody ReadingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.uploadReading(request));
    }

    /** 查询某站某气量日读数（归位升序）。 */
    @GetMapping("/stations/{stationCode}/readings")
    public List<ReadingResponse> listReadings(@PathVariable String stationCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate gasDay) {
        return service.listReadings(stationCode, gasDay);
    }

    /** 生成交接凭证（requestId 幂等）。 */
    @PostMapping("/vouchers")
    public ResponseEntity<VoucherResponse> submitVoucher(
            @Valid @RequestBody VoucherSubmitRequest request) {
        var outcome = service.submitVoucher(request);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    /** 交接方确认凭证（requestId 幂等）。 */
    @PostMapping("/vouchers/confirmations")
    public ResponseEntity<VoucherResponse> confirm(@Valid @RequestBody ConfirmRequest request) {
        var outcome = service.confirm(request);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    /** 发起争议（requestId 幂等；逾期返回 409，响应体为封账后凭证）。 */
    @PostMapping("/vouchers/disputes")
    public ResponseEntity<VoucherResponse> dispute(@Valid @RequestBody DisputeRequest request) {
        var outcome = service.dispute(request);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    /** 封账（重复封账幂等成功；requestId 可选，提供时按幂等键处理）。 */
    @PostMapping("/vouchers/{voucherNo}/seal")
    public ResponseEntity<VoucherResponse> seal(@PathVariable String voucherNo,
            @RequestParam(required = false) String requestId) {
        var outcome = service.seal(voucherNo, requestId);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    /** 双方签认的调整凭证（requestId 幂等）。 */
    @PostMapping("/vouchers/adjustments")
    public ResponseEntity<VoucherResponse> adjustment(
            @Valid @RequestBody AdjustmentRequest request) {
        var outcome = service.adjustment(request);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    /** 查询凭证。 */
    @GetMapping("/vouchers/{voucherNo}")
    public VoucherResponse getVoucher(@PathVariable String voucherNo) {
        return service.getVoucher(voucherNo);
    }

    /** 逐日偏差考核明细（按气量日升序，结果稳定）。 */
    @GetMapping("/stations/{stationCode}/assessments")
    public List<AssessmentResponse> assessments(@PathVariable String stationCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.listAssessments(stationCode, from, to);
    }
}
