package com.example.starter.unlock;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.unlock.UnlockDtos.UnlockRequest;
import com.example.starter.unlock.UnlockDtos.UnlockResponse;

/**
 * 紧急解锁接口。
 */
@RestController
@RequestMapping("/api/unlock")
public class UnlockController {

    private final UnlockService service;

    public UnlockController(UnlockService service) {
        this.service = service;
    }

    /** 紧急解锁：双人授权，解锁与闭锁恢复同一事务完成。 */
    @PostMapping
    public UnlockResponse unlock(@Valid @RequestBody UnlockRequest req) {
        return service.unlock(req);
    }
}
