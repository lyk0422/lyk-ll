package com.example.starter.ros.web.dto;

/**
 * 资源视图:类别、全限定名称与接口类型。
 */
public record ResourceView(
        String category,
        String fqName,
        String interfaceType
) {
}
