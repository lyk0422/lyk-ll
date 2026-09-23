package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

/** Topic 策略配置请求，policy 形如 DROP/LATEST/FIFO/BOUNDED(100)。 */
public record TopicPolicyRequest(
        @NotBlank String topic,
        @NotBlank String policy) {
}
