package com.example.starter.credit;

import java.util.List;

import com.example.starter.credit.CreditService.CreateCreditAccountRequest;
import com.example.starter.credit.CreditService.CreditView;
import com.example.starter.credit.CreditService.LedgerEntry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 授信额度接口：账户创建、额度查询与台账查询。
 */
@RestController
@RequestMapping("/api/credit")
public class CreditController {

    private final CreditService creditService;

    public CreditController(CreditService creditService) {
        this.creditService = creditService;
    }

    @PostMapping("/accounts")
    public ResponseEntity<CreditView> createAccount(@RequestBody CreateCreditAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(creditService.createAccount(request));
    }

    @GetMapping("/{sellerId}")
    public CreditView getCredit(@PathVariable String sellerId) {
        return creditService.getCredit(sellerId);
    }

    @GetMapping("/{sellerId}/ledger")
    public List<LedgerEntry> listLedger(@PathVariable String sellerId) {
        return creditService.listLedger(sellerId);
    }
}
