package com.example.starter.gas.web;

import com.example.starter.gas.dto.AssessmentResponse;
import com.example.starter.gas.dto.ConfirmRequest;
import com.example.starter.gas.dto.DifferenceSignOffRequest;
import com.example.starter.gas.dto.DisputeRequest;
import com.example.starter.gas.dto.ReadingSubmitRequest;
import com.example.starter.gas.dto.ReadingSubmitResponse;
import com.example.starter.gas.dto.SealRequest;
import com.example.starter.gas.dto.VerificationResponse;
import com.example.starter.gas.dto.VerificationSubmitRequest;
import com.example.starter.gas.dto.VoucherCreateRequest;
import com.example.starter.gas.dto.VoucherResponse;
import com.example.starter.gas.service.CustodyService;
import com.example.starter.gas.service.QualityVerificationService;
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

import java.time.LocalDate;
import java.util.List;

/**
 * 天然气计量交接 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class GasCustodyController {

    private final CustodyService custodyService;
    private final QualityVerificationService qualityVerificationService;

    public GasCustodyController(CustodyService custodyService,
                                QualityVerificationService qualityVerificationService) {
        this.custodyService = custodyService;
        this.qualityVerificationService = qualityVerificationService;
    }

    @PostMapping("/readings")
    public ResponseEntity<ReadingSubmitResponse> submitReading(@Valid @RequestBody ReadingSubmitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(custodyService.submitReading(request));
    }

    @PostMapping("/vouchers")
    public ResponseEntity<VoucherResponse> createVoucher(@Valid @RequestBody VoucherCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(custodyService.createVoucher(request));
    }

    @GetMapping("/vouchers/{voucherNo}")
    public VoucherResponse getVoucher(@PathVariable String voucherNo) {
        return custodyService.getVoucher(voucherNo);
    }

    @PostMapping("/vouchers/{voucherNo}/confirmations")
    public VoucherResponse confirm(@PathVariable String voucherNo, @Valid @RequestBody ConfirmRequest request) {
        return custodyService.confirm(voucherNo, request);
    }

    @PostMapping("/vouchers/{voucherNo}/disputes")
    public VoucherResponse dispute(@PathVariable String voucherNo, @Valid @RequestBody DisputeRequest request) {
        return custodyService.dispute(voucherNo, request);
    }

    @PostMapping("/vouchers/{voucherNo}/seal")
    public VoucherResponse seal(@PathVariable String voucherNo, @Valid @RequestBody SealRequest request) {
        return custodyService.seal(voucherNo, request);
    }

    @PostMapping("/vouchers/{voucherNo}/adjustments")
    public ResponseEntity<VoucherResponse> adjust(@PathVariable String voucherNo,
                                                  @Valid @RequestBody com.example.starter.gas.dto.AdjustmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(custodyService.adjust(voucherNo, request));
    }

    @PostMapping("/stations/{stationCode}/assessments")
    public AssessmentResponse assessDay(@PathVariable String stationCode,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate gasDate) {
        return custodyService.assessDay(stationCode, gasDate);
    }

    @GetMapping("/stations/{stationCode}/assessments")
    public List<AssessmentResponse> listAssessments(@PathVariable String stationCode) {
        return custodyService.listAssessments(stationCode);
    }

    @PostMapping("/verifications")
    public ResponseEntity<VerificationResponse> submitVerification(
            @Valid @RequestBody VerificationSubmitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(qualityVerificationService.submitVerification(request));
    }

    @GetMapping("/verifications/{verificationNo}")
    public VerificationResponse getVerification(@PathVariable String verificationNo) {
        return qualityVerificationService.getVerification(verificationNo);
    }

    @GetMapping("/stations/{stationCode}/verifications")
    public List<VerificationResponse> listVerifications(
            @PathVariable String stationCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate gasDate) {
        return qualityVerificationService.listVerifications(stationCode, gasDate);
    }

    @PostMapping("/verifications/{verificationNo}/sign-offs")
    public VerificationResponse signOffDifference(@PathVariable String verificationNo,
                                                  @Valid @RequestBody DifferenceSignOffRequest request) {
        return qualityVerificationService.signOffDifference(verificationNo, request);
    }
}
