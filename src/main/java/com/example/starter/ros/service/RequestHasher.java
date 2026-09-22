package com.example.starter.ros.service;

import com.example.starter.ros.web.dto.ResourceSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请求参数规范化与 SHA-256 摘要工具,用于识别同参/异参重放。
 */
public final class RequestHasher {

    private RequestHasher() {
    }

    /**
     * 注册请求的规范化参数(不含 requestId),资源按类别/全限定名/接口类型排序。
     */
    public static Map<String, Object> registerParams(String nodeId, String namespace,
                                                     String nodeName,
                                                     List<ResourceSpec> resources) {
        List<ResourceSpec> sorted = new ArrayList<>(resources);
        sorted.sort(Comparator.comparing(ResourceSpec::category)
                .thenComparing(ResourceSpec::name)
                .thenComparing(ResourceSpec::interfaceType));
        List<Map<String, String>> resourceList = new ArrayList<>();
        for (ResourceSpec spec : sorted) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("category", spec.category().trim().toUpperCase());
            item.put("name", spec.name());
            item.put("interfaceType", spec.interfaceType());
            resourceList.add(item);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nodeId", nodeId);
        params.put("namespace", namespace);
        params.put("nodeName", nodeName);
        params.put("resources", resourceList);
        return params;
    }

    /**
     * 状态迁移请求的规范化参数。
     */
    public static Map<String, Object> transitionParams(String nodeId, String targetState,
                                                       long expectedVersion) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nodeId", nodeId);
        params.put("targetState", targetState.trim().toUpperCase());
        params.put("expectedVersion", expectedVersion);
        return params;
    }

    /**
     * 仅含节点ID的操作(注销/异常退出/恢复)的规范化参数。
     */
    public static Map<String, Object> nodeParams(String nodeId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("nodeId", nodeId);
        return params;
    }

    /**
     * 计算规范化参数的 SHA-256 十六进制摘要。
     */
    public static String sha256(String normalized) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JVM不支持SHA-256", e);
        }
    }
}
