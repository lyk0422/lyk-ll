package com.example.starter.scene.service;

/**
 * 请求读取的资源（如历史版本）不存在，HTTP 404。
 */
public class SceneNotFoundException extends RuntimeException {

    public SceneNotFoundException(String message) {
        super(message);
    }
}
