package com.example.starter.arbitration.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 租约释放请求；幂等：重复释放返回成功，非持有者释放返回409。
 *
 * @param controllerId 控制器ID
 * @param resourceId   资源标识
 */
public record ReleaseRequest(
        @NotBlank String controllerId,
        @NotBlank String resourceId) {
}
