package com.example.starter.ros.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 检查点恢复请求:在当前 JVM 内按已保存检查点恢复节点。
 *
 * @param requestId 恢复操作自身的幂等ID(与节点历史 requestId 独立)
 */
public record RecoverRequest(
        @NotBlank String nodeId,
        @NotBlank String requestId
) {
}
