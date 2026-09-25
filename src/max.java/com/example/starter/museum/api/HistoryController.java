package com.example.starter.museum.api;

import com.example.starter.museum.service.HistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 借展历史复算接口：合同轨迹、越限轨迹与处置时限均可从持久化数据复算。 */
@RestController
@RequestMapping("/api/loans")
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/{contractNo}/history")
    public LoanHistoryResponse history(@PathVariable String contractNo) {
        return historyService.history(contractNo);
    }
}
