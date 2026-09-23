package com.example.starter.rosconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 热更新事务中针对单个节点的变更集。
 *
 * @param node            目标节点名
 * @param expectedVersion 调用方持有的节点配置单调版本，服务端版本不一致时拒绝整笔事务
 * @param params          待变更参数名到新值的映射；不允许出现静态参数
 */
public record ChangeSetRequest(
        @NotBlank String node,
        @NotNull Long expectedVersion,
        @NotNull Map<String, Object> params
) {
}
