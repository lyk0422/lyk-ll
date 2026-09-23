package com.example.starter.ros.service;

import com.example.starter.ros.model.ParamSchema;

import java.util.List;
import java.util.Map;

/**
 * Schema 注册中心：提供按节点/参数查找 Schema，以及默认值合并与候选快照构建。
 */
public interface SchemaService {

    List<ParamSchema> all();

    List<ParamSchema> byNode(String node);

    /** 查找 Schema；不存在返回 null。键为 组名.参数名。 */
    ParamSchema find(String node, String group, String name);

    /**
     * 读取合并视图：默认值 + 已提交值（已提交覆盖默认）。
     * 返回键 = 组名.参数名，值为强类型值。
     */
    Map<String, Object> mergedView(String node);

    /**
     * 基于已提交值与本次变更集，构建整笔更新后的候选快照（强类型）。
     *
     * @param node    节点名
     * @param changes 组名 -> 参数名 -> 原始 JSON 值
     */
    Map<String, Object> buildCandidate(String node, Map<String, Map<String, Object>> changes);
}
