package com.example.starter.payment.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.payment.dto.AdjustRequest;
import com.example.starter.payment.dto.AdjustResponse;
import com.example.starter.payment.dto.PaymentRequest;
import com.example.starter.payment.dto.PaymentResponse;
import com.example.starter.payment.service.PaymentService;

import jakarta.validation.Valid;

/**
 * 渠道交易入账与挂账调账接口。
 */
@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** 交易入账：requestId 幂等，封账批次的交易进入挂账。 */
    @PostMapping("/payments")
    public ResponseEntity<PaymentResponse> post(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = paymentService.post(request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    /** 挂账调账：将挂账交易原子转入下一开放批次。 */
    @PostMapping("/adjustments")
    public AdjustResponse adjust(@Valid @RequestBody AdjustRequest request) {
        return paymentService.adjust(request);
    }
}
