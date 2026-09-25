package com.example.starter.web;

import com.example.starter.dto.ApiDtos;
import com.example.starter.service.AccountService;
import com.example.starter.service.DepositService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 信用账户接口：建户、查询、确认快照（触发风险评估）、追加保证金入账。
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final DepositService depositService;

    public AccountController(AccountService accountService, DepositService depositService) {
        this.accountService = accountService;
        this.depositService = depositService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDtos.AccountView create(@RequestBody ApiDtos.CreateAccountRequest req) {
        return accountService.createAccount(req);
    }

    @GetMapping("/{id}")
    public ApiDtos.AccountView view(@PathVariable long id) {
        return accountService.view(id);
    }

    @PostMapping("/{id}/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDtos.SnapshotResponse confirmSnapshot(@PathVariable long id,
                                                    @RequestBody ApiDtos.SnapshotRequest req) {
        return accountService.confirmSnapshot(id, req);
    }

    @PostMapping("/{id}/deposits")
    public ResponseEntity<ApiDtos.DepositResponse> deposit(@PathVariable long id,
                                                           @RequestBody ApiDtos.DepositRequest req) {
        var response = depositService.deposit(id, req);
        // 幂等重放返回首次结果，状态码 200；首次入账 201
        return ResponseEntity.status(response.duplicated() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }
}
