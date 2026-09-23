package com.example.starter.rosconfig.dto;

import java.util.Map;

/**
 * 节点配置视图：默认值与已提交值合并后的完整参数快照。
 *
 * @param node       节点名
 * @param version    当前单调版本
 * @param group      参数组（按 Schema 归属）
 * @param params     参数名到当前有效值的映射
 * @param dynamic    参数名到是否可热更新的映射
 * @param pendingRestart 有待重启生效的静态参数名
 */
public record ConfigView(
        String node,
        long version,
        String group,
        Map<String, Object> params,
        Map<String, Boolean> dynamic,
        java.util.List<String> pendingRestart
) {
}
