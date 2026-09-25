package com.example.starter.carbon.web;

import com.example.starter.carbon.service.SurrenderService;
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
 * 配额清缴与凭证查询 REST 入口。
 */
@RestController
@RequestMapping("/api/surrender")
public class SurrenderController {

    private final SurrenderService surrenderService;

    public SurrenderController(SurrenderService surrenderService) {
        this.surrenderService = surrenderService;
    }

    /**
     * 提交清缴申请（requestId 幂等）。成功注销返回 201 与注销清单；
     * 规则拒绝（逾期、超5%、余额不足等）由异常处理器返回 422 且账户无变动；同键异参返回 409。
     */
    @PostMapping("/requests")
    public ResponseEntity<SurrenderResponse> submit(@Valid @RequestBody SurrenderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(surrenderService.submit(request));
    }

    /** 按凭证号查询清缴凭证。 */
    @GetMapping("/certificates/{certificateNo}")
    public CertificateResponse getCertificate(@PathVariable String certificateNo) {
        return surrenderService.getCertificate(certificateNo);
    }
}
