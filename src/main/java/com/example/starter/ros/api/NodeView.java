package com.example.starter.ros.api;

import java.util.List;

/**
 * 节点视图（注册/查询/迁移接口返回）。
 *
 * @param nodeId         节点唯一标识
 * @param namespace      命名空间
 * @param nodeName       节点名
 * @param lifecycleState 当前生命周期状态
 * @param version        当前乐观版本号
 * @param alive          是否存活：true=存活，false=异常退出
 * @param resources      当前仍持有的资源列表
 */
public record NodeView(
        String nodeId,
        String namespace,
        String nodeName,
        String lifecycleState,
        long version,
        boolean alive,
        List<ResourceView> resources) {
}
