package com.example.starter.scene.service;

/**
 * 请求本身非法（对象 ID 重复、关节超限、引用不存在、世界/附着互斥等），HTTP 400。
 * 整批操作在提交前校验，不产生任何写入。
 */
public class SceneValidationException extends RuntimeException {

    public SceneValidationException(String message) {
        super(message);
    }
}
