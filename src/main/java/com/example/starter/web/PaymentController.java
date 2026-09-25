package com.example.starter.web;

import com.example.starter.dto.AdjustRequest;
import com.example.starter.dto.AdjustResponse;
import com.example.starter.dto.BatchSettlementResponse;
import com.example.starter.dto.CloseBatchRequest;
import com.example.starter.dto.PaymentRequest;
import com.example.starter.dto.PaymentResponse;
import com.example.starter.dto.ReconRequest;
import com.example.starter.dto.ReconResponse;
import com.example.starter.dto.ResolveRequest;
import com.example.starter.entity.ReconDiff;
import com.example.starter.service.PaymentService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 支付清算服务 HTTP 入口（骨架）。 */
@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse postPayment(@Valid @RequestBody PaymentRequest request) {
        return paymentService.postPayment(request);
    }

    @PostMapping("/batches/close")
    public BatchSettlementResponse closeBatch(@RequestBody(required = false) CloseBatchRequest request) {
        return paymentService.closeBatch(request);
    }

    @GetMapping("/batches/{batchDate}")
    public BatchSettlementResponse getBatch(@PathVariable LocalDate batchDate) {
        return paymentService.getBatch(batchDate);
    }

    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    public AdjustResponse adjust(@Valid @RequestBody AdjustRequest request) {
        return paymentService.adjust(request);
    }

    @PostMapping("/reconciliations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReconResponse reconcile(@Valid @RequestBody ReconRequest request) {
        return paymentService.reconcile(request);
    }

    @PostMapping("/recon-diffs/{diffId}/resolve")
    public ReconDiff resolveDiff(@PathVariable Long diffId, @Valid @RequestBody ResolveRequest request) {
        return paymentService.resolveDiff(diffId, request);
    }
}
