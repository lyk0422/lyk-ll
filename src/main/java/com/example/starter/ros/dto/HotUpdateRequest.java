package com.example.starter.ros.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * 热更新事务请求。
 *
 * @param transactionId 调用方生成的幂等事务 ID
 * @param changes       各节点变更集
 * @param nodeTimeoutMs 可选，节点校验超时（毫秒）；缺省使用服务端配置
 */
public record HotUpdateRequest(
        @NotBlank String transactionId,
        @NotEmpty @Valid List<NodeChangeSet> changes,
        Long nodeTimeoutMs
) {
    /**
     * 单个节点的变更集。
     *
     * @param node            目标节点名
     * @param expectedVersion 调用方看到的单调版本号，必须与库中当前版本一致
     * @param parameters      参数组 -> 参数名 -> 新值；空 Map 表示仅做版本屏障
     */
    public record NodeChangeSet(
            @NotBlank String node,
            @NotNull Long expectedVersion,
            @NotNull Map<@NotBlank String, Map<@NotBlank String, Object>> parameters
    ) {
    }
}
