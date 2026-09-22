package com.example.starter.tf2.web;

import com.example.starter.tf2.service.Tf2CacheService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TF2 变换缓存 HTTP 入口（骨架）。
 */
@RestController
public class Tf2Controller {

    private final Tf2CacheService service;

    public Tf2Controller(Tf2CacheService service) {
        this.service = service;
    }

    /** 写入静态变换或动态样本。 */
    @PostMapping("/api/transforms")
    public ResponseEntity<Void> publish(@Valid @RequestBody TransformRequest request) {
        service.publish(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** 查询 source 到 target 在指定纳秒时刻的组合变换。 */
    @GetMapping("/api/transform")
    public TransformResponse lookup(@RequestParam String source,
                                    @RequestParam String target,
                                    @RequestParam("stampNanos") long stampNanos) {
        return service.lookup(source, target, stampNanos);
    }
}
