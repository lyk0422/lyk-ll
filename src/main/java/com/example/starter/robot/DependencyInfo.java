package com.example.starter.robot;

import java.util.List;

/**
 * 单个依赖文件的内容摘要。
 *
 * @param path     相对于受控工作目录的 POSIX 风格路径
 * @param sha256   依赖文件原始字节的 SHA-256（十六进制小写）
 * @param entry    是否为入口文件
 */
public record DependencyInfo(String path, String sha256, boolean entry) {
}
