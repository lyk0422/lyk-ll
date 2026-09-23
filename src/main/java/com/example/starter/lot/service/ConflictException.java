package com.example.starter.lot.service;

import org.springframework.http.HttpStatus;

/**
 * 冲突异常（409）：ID 冲突、乐观锁版本冲突、幂等指纹冲突、并发更新失败等。
 */
public class ConflictException extends BusinessException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
