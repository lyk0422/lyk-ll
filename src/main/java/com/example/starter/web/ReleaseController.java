package com.example.starter.web;

import com.example.starter.service.ReleaseService;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.ReleaseResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 放行接口：以 requestId 幂等，原子扣减库存并生成发血记录。
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService service;

    public ReleaseController(ReleaseService service) {
        this.service = service;
    }

    /**
     * 提交放行单。成功返回 200；同键异参、不可放行或过期返回 409。
     */
    @PostMapping
    public ReleaseResponse release(@Valid @RequestBody ReleaseRequest request) {
        return service.release(request);
    }
}
