package com.example.starter.irrigation.api;

import com.example.starter.irrigation.api.SettlementDtos.SealRequest;
import com.example.starter.irrigation.api.SettlementDtos.SettlementResponse;
import com.example.starter.irrigation.service.SettlementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轮次结束欠供结转封存接口。
 */
@RestController
@RequestMapping("/api/irrigation/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /** 封存：重复封存幂等成功，返回首次封存结果。 */
    @PostMapping("/seal")
    public SettlementResponse seal(@Valid @RequestBody SealRequest request) {
        return settlementService.seal(request);
    }

    /** 查封存结果。 */
    @GetMapping("/groups/{groupCode}/turns/{turnNo}")
    public SettlementResponse get(@PathVariable String groupCode, @PathVariable long turnNo) {
        return settlementService.getSettlement(turnNo, groupCode);
    }
}
