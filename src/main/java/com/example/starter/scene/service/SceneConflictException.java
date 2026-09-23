package com.example.starter.scene.service;

import com.example.starter.scene.model.ConflictResponse;

/**
 * 请求与当前场景状态冲突时抛出：版本过期（VERSION_MISMATCH）
 * 或同 requestId 携带不同参数（IDEMPOTENT_PARAM_MISMATCH）。
 */
public class SceneConflictException extends RuntimeException {

    private final transient ConflictResponse response;

    public SceneConflictException(ConflictResponse response) {
        super(response.reason());
        this.response = response;
    }

    public ConflictResponse getResponse() {
        return response;
    }
}
