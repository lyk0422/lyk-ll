package com.example.starter.payment.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.payment.dto.SettlementResponse;
import com.example.starter.payment.service.SettlementService;

/**
 * 批次封账与清算查询接口。
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final SettlementService settlementService;

    public BatchController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /** 封账并生成清算单；重复封账返回首次清算单（replayed=true）。 */
    @PostMapping("/{batchDate}/close")
    public SettlementResponse close(@PathVariable String batchDate) {
        return settlementService.closeBatch(batchDate);
    }

    /** 查询批次状态与清算单。 */
    @GetMapping("/{batchDate}/settlement")
    public SettlementResponse settlement(@PathVariable String batchDate) {
        return settlementService.getSettlement(batchDate);
    }
}
