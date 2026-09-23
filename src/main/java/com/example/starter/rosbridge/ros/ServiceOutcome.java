package com.example.starter.rosbridge.ros;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Service 调用的适配器回调结果。
 *
 * @param ok    适配器是否成功返回
 * @param value 成功时的返回内容（JSON）
 * @param error 失败时的错误文本
 */
public record ServiceOutcome(boolean ok, JsonNode value, String error) {

    public static ServiceOutcome success(JsonNode value) {
        return new ServiceOutcome(true, value, null);
    }

    public static ServiceOutcome failure(String error) {
        return new ServiceOutcome(false, null, error);
    }
}
