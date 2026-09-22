package com.example.starter.ros.support;

/**
 * ROS 名称规范化:相对名与节点命名空间拼接为以 / 开头的全限定名称。
 * 不接入真实 rclcpp/rclpy 名称重映射,仅模拟常用拼接规则。
 */
public final class RosNames {

    private RosNames() {
    }

    /**
     * 计算资源全限定名称。
     * 以 / 开头的名称视为全局限定名直接规范化;其余与节点命名空间拼接。
     * 结果以单个 / 开头,不含连续斜杠或尾部斜杠。
     */
    public static String fullyQualifiedName(String namespace, String resourceName) {
        String ns = normalizeNamespace(namespace);
        String name = normalizeSegments(resourceName);
        if (resourceName.trim().startsWith("/")) {
            return "/" + name;
        }
        return "/".equals(ns) ? "/" + name : ns + "/" + name;
    }

    /**
     * 规范化命名空间,结果为 / 或 /a/b 形式。
     */
    public static String normalizeNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return "/";
        }
        String segments = normalizeSegments(namespace);
        return segments.isEmpty() ? "/" : "/" + segments;
    }

    private static String normalizeSegments(String raw) {
        String[] parts = raw.trim().split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
