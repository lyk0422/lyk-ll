package com.example.starter.ros.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 节点注册请求体。
 *
 * @param nodeId      节点唯一标识
 * @param namespace   命名空间，如 /robot
 * @param nodeName    节点名（不含命名空间）
 * @param resources   资源声明列表
 * @param requestId   幂等键
 */
public record RegisterNodeRequest(
        @NotBlank String nodeId,
        @NotBlank String namespace,
        @NotBlank String nodeName,
        @NotNull List<ResourceSpec> resources,
        @NotBlank String requestId) {
}
