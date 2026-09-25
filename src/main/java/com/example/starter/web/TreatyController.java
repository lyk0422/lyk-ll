package com.example.starter.web;

import java.math.BigDecimal;

import com.example.starter.domain.Models.Policy;
import com.example.starter.domain.Models.Treaty;
import com.example.starter.service.TreatyService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合约、保单与摊回账户接口。
 */
@RestController
@RequestMapping("/api/treaties")
public class TreatyController {

    private final TreatyService treatyService;

    public TreatyController(TreatyService treatyService) {
        this.treatyService = treatyService;
    }

    /** 创建合约请求。 */
    public record CreateTreatyRequest(@NotBlank String treatyNo, @NotBlank String name,
                                      @NotNull BigDecimal cessionRate,
                                      @NotNull BigDecimal limitPerPolicy) {
    }

    /** 创建保单请求。 */
    public record CreatePolicyRequest(@NotBlank String policyNo,
                                      @NotNull BigDecimal cededPremium) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Treaty createTreaty(@RequestBody @jakarta.validation.Valid CreateTreatyRequest request) {
        return treatyService.createTreaty(request.treatyNo(), request.name(),
                request.cessionRate(), request.limitPerPolicy());
    }

    @PostMapping("/{treatyNo}/policies")
    @ResponseStatus(HttpStatus.CREATED)
    public Policy createPolicy(@PathVariable String treatyNo,
                               @RequestBody @jakarta.validation.Valid CreatePolicyRequest request) {
        return treatyService.createPolicy(treatyNo, request.policyNo(), request.cededPremium());
    }

    @GetMapping("/{treatyNo}/account")
    public TreatyService.AccountView getAccount(@PathVariable String treatyNo) {
        return treatyService.getAccount(treatyNo);
    }
}
