package com.example.starter.transition.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 过渡料处置请求：按卷号登记过渡纸卷，服务端强制降级、入非正品库。
 */
public record DisposeRollsRequest(
        @NotEmpty @Valid List<RollItem> rolls) {

    /**
     * 单个过渡纸卷登记项。
     *
     * @param rollNo  卷号，全局唯一
     * @param lengthM 纸卷长度，单位 m，可空
     */
    public record RollItem(
            @NotBlank String rollNo,
            @NotNull BigDecimal lengthM) {
    }
}
