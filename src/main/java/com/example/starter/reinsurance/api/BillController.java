package com.example.starter.reinsurance.api;

import com.example.starter.reinsurance.service.BillService;
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
 * 账单接口：季度出账、再保人确认/异议、下一期调整单。
 */
@RestController
@RequestMapping("/api/reinsurance/bills")
public class BillController {

    private final BillService billService;

    public BillController(BillService billService) {
        this.billService = billService;
    }

    @PostMapping("/generate")
    public ResponseEntity<BillResponse> generate(@Valid @RequestBody GenerateBillRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(billService.generate(request));
    }

    @PostMapping("/{billNo}/confirm")
    public BillResponse confirm(
            @PathVariable String billNo, @Valid @RequestBody ConfirmBillRequest request) {
        return billService.confirm(billNo, request);
    }

    @PostMapping("/{billNo}/dispute")
    public BillResponse dispute(
            @PathVariable String billNo, @Valid @RequestBody DisputeBillRequest request) {
        return billService.dispute(billNo, request);
    }

    @PostMapping("/adjustments")
    public ResponseEntity<BillResponse> createAdjustment(
            @Valid @RequestBody CreateAdjustmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(billService.createAdjustment(request));
    }

    @GetMapping("/{billNo}")
    public BillResponse getBill(@PathVariable String billNo) {
        return billService.getBill(billNo);
    }
}
