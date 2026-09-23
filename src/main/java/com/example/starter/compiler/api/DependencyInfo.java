package com.example.starter.compiler.api;

/**
 * 单个传递依赖的内容清单条目。
 *
 * @param path   依赖文件相对受控工作目录的规范化路径（统一使用 / 分隔）
 * @param sha256 依赖文件原始字节的 SHA-256（十六进制）
 */
public record DependencyInfo(String path, String sha256) {
}
