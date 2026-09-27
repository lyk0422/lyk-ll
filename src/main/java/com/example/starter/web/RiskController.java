package com.example.starter.web;

import com.example.starter.service.RiskService;
import com.example.starter.web.dto.RiskBindRequest;
import com.example.starter.web.dto.RiskChangeRequest;
import com.example.starter.web.dto.RiskChecklistResponse;
import com.example.starter.web.dto.RiskResultRequest;
import com.example.starter.web.dto.RiskResultResponse;
import com.example.starter.web.dto.RiskSummaryView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 风险抽检接口：风险绑定、等级变更（原子扩充/补充清单）、逐只结果与清单/隔离原因查询。
 */
@RestController
@RequestMapping("/api/batches/{batchNo}/risk")
public class RiskController {

    private final RiskService service;

    public RiskController(RiskService service) {
        this.service = service;
    }

    /** 风险绑定（幂等）：宰前检疫合格后冻结首份抽检清单。 */
    @PostMapping("/bind")
    public RiskChecklistResponse bind(@PathVariable String batchNo,
                                      @Valid @RequestBody RiskBindRequest request) {
        return service.bind(batchNo, request);
    }

    /** 风险等级变更（幂等）：未完成清单原子扩充，已完成清单另开补充清单并再次锁出厂。 */
    @PostMapping("/changes")
    public RiskChecklistResponse changeLevel(@PathVariable String batchNo,
                                             @Valid @RequestBody RiskChangeRequest request) {
        return service.changeLevel(batchNo, request);
    }

    /** 逐只抽检结果提交（幂等）：任一阳性整批专项隔离并锁出厂。 */
    @PostMapping("/results")
    public RiskResultResponse submitResult(@PathVariable String batchNo,
                                           @Valid @RequestBody RiskResultRequest request) {
        return service.submitResult(batchNo, request);
    }

    /** 抽检清单及专项隔离原因查询。 */
    @GetMapping
    public RiskSummaryView summary(@PathVariable String batchNo) {
        return service.getSummary(batchNo);
    }
}
