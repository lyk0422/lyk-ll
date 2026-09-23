package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 上游 ACK 请求。
 */
public class AckRequest {

    /** 被确认消息的全局唯一 dedupKey。 */
    @NotBlank
    private String dedupKey;

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }
}
