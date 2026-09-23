package com.example.starter.robot;

import java.util.List;

/**
 * 编译成功响应。
 *
 * @param requestId       请求标识
 * @param entry           入口文件相对路径
 * @param urdf            规范化展开后的 URDF 文本
 * @param urdfSha256      URDF 字节的稳定 SHA-256（十六进制小写）
 * @param cacheKey        覆盖入口、全部传递依赖内容摘要与规范化参数的缓存键
 * @param cacheHit        是否复用了已有缓存
 * @param dependencies    全部传递依赖（按相对路径排序）
 * @param expansionChain  展开链路（入口在前，按 include 前序）
 */
public record CompileResponse(
        String requestId,
        String entry,
        String urdf,
        String urdfSha256,
        String cacheKey,
        boolean cacheHit,
        List<DependencyInfo> dependencies,
        List<String> expansionChain) {
}
