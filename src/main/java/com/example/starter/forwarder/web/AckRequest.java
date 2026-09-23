package com.example.starter.forwarder.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 上游 ACK 请求；ACK 可重复或乱序到达，按 dedupKey 精确确认。
 */
public class AckRequest {

    @NotBlank(message = "dedupKey 不能为空")
    private String dedupKey;

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }
}
