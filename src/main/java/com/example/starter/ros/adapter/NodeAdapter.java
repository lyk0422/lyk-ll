package com.example.starter.ros.adapter;

import java.util.Map;

/**
 * 可注入的 ROS 节点适配器：模拟真实 ROS 2 节点对候选参数的接受、拒绝与超时，
 * 不依赖真实 ROS。
 */
public interface NodeAdapter {

    /**
     * 请节点校验并暂存候选配置（模拟 rcl 的 set_parameters 回调）。
     *
     * @param nodeId       目标节点
     * @param candidate    合并默认值后的候选完整快照（参数名 -> 标量值）
     * @param timeoutMillis 配置时限；超过该时间无响应视为超时
     * @throws com.example.starter.ros.NodeRejectedException 节点显式拒绝
     * @throws com.example.starter.ros.NodeTimeoutException  时限内无响应
     */
    void validate(String nodeId, Map<String, Object> candidate, long timeoutMillis);

    /**
     * 提交已暂存的候选配置；仅在全部节点校验通过后被调用。
     */
    void apply(String nodeId, Map<String, Object> candidate, long newVersion);

    /**
     * 节点“已应用值”读回，用于回滚后断言节点侧状态未被污染。
     * 未应用过任何配置时返回空 Map。
     */
    Map<String, Object> appliedValues(String nodeId);
}
