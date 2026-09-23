package com.example.starter.compiler.urdf;

/**
 * URDF 规范化序列化器：将展开后的文档重排为确定性格式，
 * 保证相同逻辑模型总是产生相同字节（不含时间戳或绝对路径）。
 */
public interface UrdfCanonicalizer {

    /**
     * 将展开后的 URDF 文本规范化。
     *
     * @param expandedXml 展开后的 XML 文本
     * @return 规范化后的 XML 字节（UTF-8）
     */
    byte[] canonicalize(String expandedXml);
}
