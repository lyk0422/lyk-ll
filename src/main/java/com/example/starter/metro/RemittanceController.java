package com.example.starter.metro;

import com.example.starter.metro.dto.RemittanceOpenRequest;
import com.example.starter.metro.dto.RemittanceOpenResponse;
import com.example.starter.metro.dto.RemittanceReceiptRequest;
import com.example.starter.metro.dto.RemittanceReceiptResponse;
import com.example.starter.metro.dto.RemittanceView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 缴回对账 API：对账单建立、合成银行回执到账与对账查询。
 */
@RestController
@RequestMapping("/api/metro/remittance")
public class RemittanceController {

    private final RemittanceService remittanceService;

    public RemittanceController(RemittanceService remittanceService) {
        this.remittanceService = remittanceService;
    }

    /** 建立缴回对账单。 */
    @PostMapping("/open")
    public RemittanceOpenResponse open(@Valid @RequestBody RemittanceOpenRequest request) {
        return remittanceService.open(request);
    }

    /** 提交合成银行回执（可分次、乱序到账）。 */
    @PostMapping("/receipts")
    public RemittanceReceiptResponse submitReceipt(@Valid @RequestBody RemittanceReceiptRequest request) {
        return remittanceService.submitReceipt(request);
    }

    /** 查询对账单应缴/已缴/余额与流水分配链。 */
    @GetMapping("/{statementNo}")
    public RemittanceView view(@PathVariable String statementNo) {
        return remittanceService.view(statementNo);
    }
}
