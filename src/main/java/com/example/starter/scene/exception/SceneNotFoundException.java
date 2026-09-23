package com.example.starter.scene.exception;

/** 请求的历史版本不存在，对应 HTTP 404。 */
public class SceneNotFoundException extends RuntimeException {

    public SceneNotFoundException(String message) {
        super(message);
    }
}
