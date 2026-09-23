package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Topic 离线保留策略配置请求。
 */
public class TopicPolicyRequest {

    /** 策略名称：DROP / LATEST / FIFO / BOUNDED。 */
    @NotBlank
    private String strategy;

    /** BOUNDED 策略的保留条数 n，其余策略可为空。单位：条。 */
    @Positive
    private Integer bound;

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public Integer getBound() {
        return bound;
    }

    public void setBound(Integer bound) {
        this.bound = bound;
    }
}
