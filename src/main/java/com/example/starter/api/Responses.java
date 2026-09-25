package com.example.starter.api;

import org.springframework.http.ResponseEntity;

import com.example.starter.service.Outcome;

/**
 * 控制器工具：把业务执行结果转为 HTTP 响应（保留首次/重放状态码）。
 */
final class Responses {

    private Responses() {
    }

    static ResponseEntity<Object> from(Outcome outcome) {
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }
}
