package com.example.starter.ros.service;

import com.example.starter.ros.api.EventView;
import com.example.starter.ros.api.NodeView;
import com.example.starter.ros.api.RegisterNodeRequest;
import com.example.starter.ros.api.RestoreResult;
import com.example.starter.ros.api.TransitionRequest;

import java.util.List;

/**
 * 注册中心业务服务：节点注册/注销/生命周期/异常退出/检查点/事件订阅。
 */
public interface NodeRegistryService {

    /** 注册节点并声明资源，按 requestId 幂等。 */
    NodeView register(RegisterNodeRequest request);

    /** 注销节点并释放全部资源，重复注销不得产生重复资源/事件，按 requestId 幂等。 */
    NodeView deregister(String nodeId, String requestId);

    /** 生命周期相邻迁移，按 requestId 幂等；区分非法迁移/已终结/版本冲突。 */
    NodeView transition(String nodeId, TransitionRequest request);

    /** 模拟节点异常退出：同一事务清理全部资源且只发布一次清理事件。 */
    NodeView crash(String nodeId, String requestId);

    /** 查询节点当前视图。 */
    NodeView getNode(String nodeId);

    /** 列出全部节点。 */
    List<NodeView> listNodes();

    /** 订阅资源变更事件：返回 revision 严格大于 afterRevision 的事件，最多 limit 条。 */
    List<EventView> eventsAfter(long afterRevision, int limit);

    /** 保存检查点（节点、版本、已处理 requestId、事件序列）。 */
    void saveCheckpoint(String checkpointId, String requestId);

    /** 在当前 JVM 内从检查点恢复，不重放已确认事件。 */
    RestoreResult restoreCheckpoint(String checkpointId, String requestId);
}
