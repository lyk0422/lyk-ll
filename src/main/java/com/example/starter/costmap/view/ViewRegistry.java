package com.example.starter.costmap.view;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同一 JVM 内的恢复视图注册表：仅记录 viewId -&gt; snapshotId 映射，
 * 视图数据每次查询都从 H2 快照表读取（快照不可变），JVM 退出后映射随内存消失。
 */
@Component
public class ViewRegistry {

    private final ConcurrentHashMap<String, String> views = new ConcurrentHashMap<>();

    /** 基于已保存快照ID创建恢复视图，返回新视图ID。 */
    public String register(String snapshotId) {
        String viewId = "view-" + UUID.randomUUID();
        views.put(viewId, snapshotId);
        return viewId;
    }

    /** 按视图ID查找其绑定的快照ID。 */
    public Optional<String> snapshotOf(String viewId) {
        return Optional.ofNullable(views.get(viewId));
    }
}
