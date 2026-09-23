package com.example.starter.compiler.api;

import java.util.List;

/**
 * 编译结果。
 *
 * @param requestId     回显请求标识
 * @param urdf          规范 URDF 文本（确定性序列化）
 * @param sha256        规范 URDF 字节的 SHA-256（十六进制）
 * @param dependencies  全部传递依赖清单（含入口，按规范化路径排序）
 * @param expansionChain include 展开链路（按文件出现顺序，入口在首）
 * @param cacheHit      是否复用缓存
 */
public record CompileResult(
        String requestId,
        String urdf,
        String sha256,
        List<DependencyInfo> dependencies,
        List<String> expansionChain,
        boolean cacheHit) {
}
