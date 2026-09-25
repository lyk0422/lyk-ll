package com.example.starter.error;

import com.example.starter.domain.UnitStatus;

/**
 * 冲突类业务异常（409）：条码重复、幂等键参数不一致、放行被拒等。
 */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message, UnitStatus currentStatus) {
        super(code, message, currentStatus);
    }

    @Override
    public int httpStatus() {
        return 409;
    }
}
