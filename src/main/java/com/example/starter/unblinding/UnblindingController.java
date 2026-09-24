package com.example.starter.unblinding;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应急揭盲接口。
 */
@RestController
@RequestMapping("/api/trials/{trialCode}/unblinding")
public class UnblindingController {

    private final UnblindingService service;

    public UnblindingController(UnblindingService service) {
        this.service = service;
    }

    /**
     * 应急揭盲：两名不同授权人在同一事务内先后确认方可生效；重复请求返回首次结果。
     */
    @PostMapping
    public UnblindResponse unblind(@PathVariable String trialCode,
            @Valid @RequestBody UnblindRequest request) {
        return service.unblind(trialCode, request);
    }
}
