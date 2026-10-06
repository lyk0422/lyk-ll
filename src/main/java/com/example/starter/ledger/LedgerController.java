package com.example.starter.ledger;

import com.example.starter.ledger.LedgerDtos.LedgerResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 台账接口。
 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping
    public LedgerResponse ledger() {
        return ledgerService.ledger();
    }
}
