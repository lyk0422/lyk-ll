package com.example.starter.lot.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 隔离/解除隔离请求。
 *
 * @param lotId  被直接操作的批次（隔离来源）
 * @param action 动作：QUARANTINE 设置隔离 / RELEASE 解除隔离
 * @param reason 原因（可空）
 */
public record QuarantineRequest(
        @NotBlank @Size(max = 64) String lotId,
        @NotNull Action action,
        @Size(max = 255) String reason
) {

    /** 隔离动作。 */
    public enum Action {
        /** 设置隔离：沿谱系传播到全部后代。 */
        QUARANTINE,
        /** 解除隔离：仅解除该来源；后代需自身及所有祖先来源均解除后才恢复可用。 */
        RELEASE
    }
}
