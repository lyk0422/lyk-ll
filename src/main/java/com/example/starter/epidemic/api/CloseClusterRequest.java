package com.example.starter.epidemic.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 关闭聚集事件请求。关闭为终态，重复关闭返回幂等成功。
 *
 * @param operator 操作人，非空
 * @param reason   关闭原因，可空
 */
public record CloseClusterRequest(
        @NotBlank String operator,
        String reason
) {
}
