package com.example.starter.invoice.web;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.invoice.service.RedFlushService;

/**
 * 红字信息表接口：创建、双方确认与查询。
 */
@RestController
@RequestMapping("/api/red-info-tables")
public class RedInfoController {

    private final RedFlushService redFlushService;

    public RedInfoController(RedFlushService redFlushService) {
        this.redFlushService = redFlushService;
    }

    /**
     * 创建红字信息表（requestId 幂等）。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RedInfoResponse create(@Valid @RequestBody CreateRedInfoRequest request) {
        return redFlushService.createInfoTable(request);
    }

    /**
     * 购销双方确认；双方确认通过后在同一事务内开具红票并恢复额度。
     */
    @PostMapping("/{infoNo}/confirm")
    public RedInfoResponse confirm(@PathVariable String infoNo,
                                   @Valid @RequestBody ConfirmRequest request) {
        return redFlushService.confirm(infoNo, request);
    }

    /**
     * 查询信息表（含确认轨迹）。
     */
    @GetMapping("/{infoNo}")
    public RedInfoResponse get(@PathVariable String infoNo) {
        return redFlushService.getByInfoNo(infoNo);
    }
}
