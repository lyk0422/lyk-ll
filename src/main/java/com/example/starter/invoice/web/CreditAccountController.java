package com.example.starter.invoice.web;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.invoice.service.CreditAccountService;

/**
 * 授信账户接口：开户与额度查询。
 */
@RestController
@RequestMapping("/api/credit-accounts")
public class CreditAccountController {

    private final CreditAccountService creditAccountService;

    public CreditAccountController(CreditAccountService creditAccountService) {
        this.creditAccountService = creditAccountService;
    }

    /**
     * 新建授信账户（合成数据入口）。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreditAccountResponse create(@Valid @RequestBody CreateCreditAccountRequest request) {
        return creditAccountService.create(request);
    }

    /**
     * 查询授信账户与可用额度。
     */
    @GetMapping("/{sellerTaxNo}")
    public CreditAccountResponse get(@PathVariable String sellerTaxNo) {
        return creditAccountService.getBySellerTaxNo(sellerTaxNo);
    }
}
