package com.example.starter.lot.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 转换（拆并批）请求：原子消费多个父批次并产生多个新子批次。
 *
 * @param requestId   幂等请求键；同键同完整请求返回首次结果，同键异参返回 409
 * @param unit        本次转换计量单位，所有父子批次单位必须一致
 * @param allowedLoss 允许损耗（非负，三位小数）；总消费-总产出差额不得超过该值
 * @param inputs      父批次消费列表，每项给出批次、消费量与期望版本
 * @param outputs     子批次产出列表，每项给出新批次 ID 与产出量
 */
public record ConversionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 16) String unit,
        @NotNull @DecimalMin(value = "0.000", message = "允许损耗不得为负") BigDecimal allowedLoss,
        @NotEmpty @Valid List<InputItem> inputs,
        @NotEmpty @Valid List<OutputItem> outputs
) {

    /**
     * 父批次消费项。
     *
     * @param lotId           父批次 ID
     * @param quantity        本次消费量，必须为正
     * @param expectedVersion 请求时该父批次的期望版本
     */
    public record InputItem(
            @NotBlank @Size(max = 64) String lotId,
            @NotNull @DecimalMin(value = "0.001", message = "消费量必须为正") BigDecimal quantity,
            @NotNull Integer expectedVersion
    ) {
    }

    /**
     * 子批次产出项。
     *
     * @param lotId    新子批次 ID（不得与既有批次冲突）
     * @param quantity 产出量，必须为正
     */
    public record OutputItem(
            @NotBlank @Size(max = 64) String lotId,
            @NotNull @DecimalMin(value = "0.001", message = "产出量必须为正") BigDecimal quantity
    ) {
    }
}
