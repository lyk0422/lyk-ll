package com.example.starter.ros.domain;

import java.time.LocalDateTime;

/**
 * 节点声明的单个资源。资源键 = 资源类别 + 全限定名称 + 接口类型。
 */
public record NodeResource(
        Long id,
        String nodeId,
        ResourceCategory category,
        String fqName,
        String interfaceType,
        LocalDateTime createdAt
) {
}
