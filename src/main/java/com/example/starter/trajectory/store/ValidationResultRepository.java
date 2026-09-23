package com.example.starter.trajectory.store;

import com.example.starter.trajectory.dto.ValidationResult;

/**
 * 校验请求与结果持久化，支撑幂等：同键同参返回原结果，同键异参冲突。
 */
public interface ValidationResultRepository {

    /** 已存储的请求记录。 */
    record StoredValidation(String requestHash, ValidationResult result) {
    }

    /** 按幂等键读取，不存在返回 {@code null}。 */
    StoredValidation findByRequestId(String requestId);

    /**
     * 保存请求载荷与结果；并发下键重复时由调用方重读处理。
     *
     * @return 是否插入成功（键已存在返回 {@code false}）
     */
    boolean save(String requestId, String ruleVersion, String requestHash,
                 String requestJson, ValidationResult result);
}
