package com.example.starter.web;

import java.util.List;

import com.example.starter.service.QueryService;
import com.example.starter.web.dto.ApiDtos.BladeStatusResponse;
import com.example.starter.web.dto.ApiDtos.CurtailmentView;
import com.example.starter.web.dto.ApiDtos.RecurrenceStatsResponse;
import com.example.starter.web.dto.ApiDtos.RepairLedgerView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 叶片维度查询入口：限功率状态、限功率链、维修台账、复发统计。
 */
@RestController
@RequestMapping("/api/blades")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{bladeId}/status")
    public BladeStatusResponse status(@PathVariable long bladeId) {
        return queryService.bladeStatus(bladeId);
    }

    @GetMapping("/{bladeId}/curtailments")
    public List<CurtailmentView> curtailments(@PathVariable long bladeId) {
        return queryService.curtailmentChain(bladeId);
    }

    @GetMapping("/{bladeId}/repairs")
    public List<RepairLedgerView> repairs(@PathVariable long bladeId) {
        return queryService.repairLedger(bladeId);
    }

    @GetMapping("/{bladeId}/recurrence-stats")
    public RecurrenceStatsResponse recurrenceStats(@PathVariable long bladeId) {
        return queryService.recurrenceStats(bladeId);
    }
}
