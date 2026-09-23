package com.example.starter.robot;

import java.util.List;
import java.util.Map;

/**
 * 受限 Xacro 展开的中间结果。
 *
 * @param urdf           展开并完成参数替换后的 URDF 文本
 * @param dependencies   全部传递依赖（入口在前）
 * @param expansionChain 展开链路（入口在前，按 include 前序）
 * @param parameters     合并后的有效参数（文件默认值被请求覆盖后的规范化视图）
 */
public record ExpansionResult(
        String urdf,
        List<DependencyInfo> dependencies,
        List<String> expansionChain,
        Map<String, String> parameters) {
}
