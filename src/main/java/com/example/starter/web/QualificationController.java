package com.example.starter.web;

import com.example.starter.service.ExpiryService;
import com.example.starter.service.RenewalService;
import com.example.starter.web.dto.QualificationDtos.ExpireRequest;
import com.example.starter.web.dto.QualificationDtos.ExpireResponse;
import com.example.starter.web.dto.QualificationDtos.QualificationResponse;
import com.example.starter.web.dto.QualificationDtos.RenewalRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行资格续期与到期处理接口。
 */
@RestController
@RequestMapping("/api/qualifications")
public class QualificationController {

    private final RenewalService renewalService;
    private final ExpiryService expiryService;

    public QualificationController(RenewalService renewalService, ExpiryService expiryService) {
        this.renewalService = renewalService;
        this.expiryService = expiryService;
    }

    /**
     * 资格续期；仅受理到期前 30 日内（含恰第 30 日）的申请。
     */
    @PostMapping("/{qualificationId}/renewals")
    public QualificationResponse renew(@PathVariable long qualificationId,
                                       @Valid @RequestBody RenewalRequest request) {
        return renewalService.renew(qualificationId, request.requestId());
    }

    /**
     * 处理到期资格：失效、停运留痕、学生改派与通知（同一事务）。
     */
    @PostMapping("/expire")
    public ExpireResponse expire(@Valid @RequestBody ExpireRequest request) {
        return expiryService.expireQualifications(request.requestId());
    }
}
