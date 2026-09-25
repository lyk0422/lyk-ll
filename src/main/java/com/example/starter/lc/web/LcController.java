package com.example.starter.lc.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.lc.service.LcService;
import com.example.starter.lc.web.dto.CreateLcRequest;
import com.example.starter.lc.web.dto.LcResponse;
import com.example.starter.lc.web.dto.NoticeRequest;
import com.example.starter.lc.web.dto.PayRequest;
import com.example.starter.lc.web.dto.PresentRequest;
import com.example.starter.lc.web.dto.PresentationResponse;
import com.example.starter.lc.web.dto.RefundRequest;
import com.example.starter.lc.web.dto.WaiverRequest;

import jakarta.validation.Valid;

/**
 * 信用证审单不符点与拒付决策 REST 入口（全部合成数据，无外部银行系统依赖）。
 */
@RestController
@RequestMapping("/api/lc")
public class LcController {

    private final LcService lcService;

    public LcController(LcService lcService) {
        this.lcService = lcService;
    }

    /** 开立合成信用证。 */
    @PostMapping("/accounts")
    public ResponseEntity<LcResponse> createLc(@Valid @RequestBody CreateLcRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(lcService.createLc(req));
    }

    /** 查询信用证与可用额度。 */
    @GetMapping("/accounts/{lcNo}")
    public LcResponse getLc(@PathVariable String lcNo) {
        return lcService.getLc(lcNo);
    }

    /** 受益人交单 / 拒付后改单重交（新批次，重新起算审单期）。 */
    @PostMapping("/presentations")
    public PresentationResponse present(@Valid @RequestBody PresentRequest req) {
        return lcService.present(req);
    }

    /** 查询交单批次状态。 */
    @GetMapping("/presentations/{presentationNo}")
    public PresentationResponse getPresentation(@PathVariable String presentationNo) {
        return lcService.getPresentation(presentationNo);
    }

    /** 银行一次性发出拒付通知（超期发出无效，自动视为接受）。 */
    @PostMapping("/presentations/notice")
    public PresentationResponse notice(@Valid @RequestBody NoticeRequest req) {
        return lcService.notice(req);
    }

    /** 开证申请人放弃全部不符点（部分放弃无效）。 */
    @PostMapping("/presentations/waive")
    public PresentationResponse waive(@Valid @RequestBody WaiverRequest req) {
        return lcService.waive(req);
    }

    /** 银行付款承兑（终态，幂等，额度不足整笔拒绝）。 */
    @PostMapping("/presentations/pay")
    public PresentationResponse pay(@Valid @RequestBody PayRequest req) {
        return lcService.pay(req);
    }

    /** 拒付退单结案（终态，与放弃并发时形成唯一终态）。 */
    @PostMapping("/presentations/refund")
    public PresentationResponse refund(@Valid @RequestBody RefundRequest req) {
        return lcService.refund(req);
    }
}
