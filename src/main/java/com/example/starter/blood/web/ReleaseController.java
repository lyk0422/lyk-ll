package com.example.starter.blood.web;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.blood.service.ReleaseResult;
import com.example.starter.blood.service.ReleaseService;

/**
 * 放行接口：requestId 幂等，放行成功原子扣减库存并生成发血记录。
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releaseService;

    public ReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    /**
     * 提交放行请求。同键同参返回首次结果；同键异参返回 409；
     * 业务拒绝（状态不可放行/已过期）返回 200 且携带单位当前真实状态。
     *
     * @param request 放行请求
     * @return 放行处理结果
     */
    @PostMapping
    public ReleaseResult release(@Valid @RequestBody ReleaseRequestBody request) {
        return releaseService.release(request.requestId(), request.barcode(), request.windowStart(),
                request.windowEnd());
    }
}
