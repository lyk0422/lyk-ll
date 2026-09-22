package com.example.starter.ros.api;

/**
 * 资源视图。
 *
 * @param resourceCategory   资源类别
 * @param fullyQualifiedName 全限定名称
 * @param interfaceType      接口类型
 */
public record ResourceView(
        String resourceCategory,
        String fullyQualifiedName,
        String interfaceType) {
}
