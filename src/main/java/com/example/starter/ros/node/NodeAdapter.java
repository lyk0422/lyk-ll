package com.example.starter.ros.node;

import java.util.Map;

/**
 * 可注入的 ROS 节点适配器，用于在没有真实 ROS 的环境下模拟节点
 * 对候选配置的接受、拒绝、超时，以及配置应用结果。
 */
public interface NodeAdapter {

    /**
     * 请节点校验候选配置；实现必须在 timeoutMs 时限内返回，否则由上层判定为 TIMEOUT。
     *
     * @param node      节点名
     * @param candidate 候选快照（键为 组名.参数名，值为强类型值）
     * @param timeoutMs 配置时限（毫秒）
     */
    ValidationOutcome validate(String node, Map<String, Object> candidate, long timeoutMs);

    /**
     * 全部节点校验通过且数据库原子提交成功后，应用该节点配置。
     */
    void apply(String node, Map<String, Object> applied);
}
