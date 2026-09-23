package com.example.starter.compiler.xacro;

import com.example.starter.compiler.api.DependencyInfo;

import java.util.List;

/**
 * Xacro 受限展开的产物。
 *
 * @param urdf          展开后的 URDF 文本（尚未做规范化序列化）
 * @param dependencies  全部传递依赖（含入口），路径与内容摘要
 * @param expansionChain include 展开链路（入口在首，按解析顺序）
 */
public record ExpansionResult(String urdf, List<DependencyInfo> dependencies, List<String> expansionChain) {
}
