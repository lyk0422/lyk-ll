package com.example.starter.web;

import com.example.starter.dto.ColdChainSubmitRequest;
import com.example.starter.dto.ServiceResult;
import com.example.starter.service.ColdChainService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 冷链运输片段核验 REST 入口：受控包装子批的运输证据登记/补录/更正与台账查询。
 * 签发门禁仍在批签发入口同事务检查当前温证，本控制器不改变原公示、异议及通关核销条件。
 */
@RestController
@RequestMapping("/api/coldchain")
public class ColdChainController {

    private final ColdChainService coldChainService;

    public ColdChainController(ColdChainService coldChainService) {
        this.coldChainService = coldChainService;
    }

    /**
     * 提交运输证据（requestId 幂等）：首次登记 expectedVersion=0（版本 1 起），
     * 补录/更正携带当前版本，成功加 1，旧版 409；证据不合格记录缺口并置 UNQUALIFIED。
     */
    @PostMapping("/subbatches/{batchNo}/transports")
    public ResponseEntity<Object> submit(@PathVariable String batchNo,
                                         @Valid @RequestBody ColdChainSubmitRequest request) {
        return toEntity(coldChainService.submit(batchNo, request));
    }

    /** 查询冷链台账当前版本视图（缺口、越界点及合格冻结版本）。 */
    @GetMapping("/subbatches/{batchNo}/transports")
    public ResponseEntity<Object> ledger(@PathVariable String batchNo) {
        return ResponseEntity.ok(coldChainService.getLedger(batchNo));
    }

    private static ResponseEntity<Object> toEntity(ServiceResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
