package com.example.starter.slam.optim;

import com.example.starter.slam.store.GraphStore;
import org.springframework.stereotype.Component;

/**
 * 按图当前有效内容构建优化输入快照（仅关键帧与 VALID 边）。
 */
@Component
public class SnapshotFactory {

    private final GraphStore graphStore;

    public SnapshotFactory(GraphStore graphStore) {
        this.graphStore = graphStore;
    }

    /**
     * 构建指定图的当前快照。
     *
     * @param graphId 图 ID
     * @return 固化前快照
     */
    public GraphSnapshot build(String graphId) {
        long version = graphStore.currentVersion(graphId);
        return new GraphSnapshot(graphId, version,
                graphStore.loadFrames(graphId), graphStore.loadValidEdges(graphId));
    }
}
