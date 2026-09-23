package com.example.starter.rosconfig.adapter;

import java.util.Map;

/**
 * 模拟 ROS 2 节点的可注入适配器：负责候选配置校验与应用，不依赖真实 ROS。
 */
public interface NodeAdapter {

    /**
     * 请节点校验候选完整配置快照。实现可能接受、拒绝或在配置时限内不响应。
     *
     * @param node      节点名
     * @param candidate 合并默认值与变更后的完整候选快照
     * @return 节点接受时返回 true；拒绝返回 false（原因见 {@link #lastRejection(String)}）
     * @throws InterruptedException 等待响应期间被中断
     */
    boolean validate(String node, Map<String, Object> candidate) throws InterruptedException;

    /**
     * 全部节点校验通过且数据库原子提交成功后，将配置应用到节点。
     */
    void apply(String node, Map<String, Object> applied);

    /**
     * 节点最近一次拒绝原因，仅用于结果回显。
     */
    String lastRejection(String node);

    /**
     * 节点当前已应用值的只读视图，供测试断言回滚后节点状态保持原状。
     */
    Map<String, Object> appliedView(String node);
}
