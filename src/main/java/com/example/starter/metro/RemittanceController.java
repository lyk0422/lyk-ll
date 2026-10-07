package com.example.starter.metro;

import com.example.starter.metro.dto.RemittanceReceiptRequest;
import com.example.starter.metro.dto.RemittanceReceiptResponse;
import com.example.starter.metro.dto.RemittanceStatementOpenRequest;
import com.example.starter.metro.dto.RemittanceStatementOpenResponse;
import com.example.starter.metro.dto.RemittanceStatementView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 缴回对账 API：对账单建立、合成银行回执入账与对账单查询。
 */
@RestController
@RequestMapping("/api/metro/remittances")
public class RemittanceController {

    private final RemittanceService remittanceService;

    public RemittanceController(RemittanceService remittanceService) {
        this.remittanceService = remittanceService;
    }

    /** 建立缴回对账单（仅非空已封存调账批次，每批最多一单）。 */
    @PostMapping("/statements/open")
    public RemittanceStatementOpenResponse openStatement(
            @Valid @RequestBody RemittanceStatementOpenRequest request) {
        return remittanceService.openStatement(request);
    }

    /** 合成银行回执入账（整笔入账或整笔零入账）。 */
    @PostMapping("/receipts")
    public RemittanceReceiptResponse receipt(@Valid @RequestBody RemittanceReceiptRequest request) {
        return remittanceService.receipt(request);
    }

    /** 查询缴回对账单（应缴、已缴、余额及流水分配链）。 */
    @GetMapping("/statements/{statementNo}")
    public RemittanceStatementView statementView(@PathVariable String statementNo) {
        return remittanceService.statementView(statementNo);
    }
}
