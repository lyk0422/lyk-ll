package com.example.starter.ros.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 节点注册请求。requestId 用于幂等:相同 requestId 同参重放返回原结果,异参返回 409。
 */
public record RegisterNodeRequest(
        @NotBlank String nodeId,
        @NotBlank String namespace,
        @NotBlank String nodeName,
        @Valid List<ResourceSpec> resources,
        @NotBlank String requestId
) {
    @NotNull
    public List<ResourceSpec> safeResources() {
        return resources == null ? List.of() : resources;
    }
}
