package com.example.starter.scene.exception;

/**
 * 请求内容非法（缺失字段、非有限数值、引用不存在、违反唯一性等），整批拒绝，HTTP 400。
 */
public class SceneValidationException extends RuntimeException {

    public SceneValidationException(String message) {
        super(message);
    }
}
