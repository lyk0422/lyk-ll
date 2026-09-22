package com.example.starter.costmap.view;

import com.example.starter.costmap.model.MapVersion;

import java.util.Map;

/**
 * 快照冻结视图：缺失的冻结格点回落到静态膨胀层（静态层不可变）。
 * 快照发布后，实时表的更新与 TTL 清理均不影响本视图。
 */
public class SnapshotView implements CostView {

    private final String snapshotId;
    private final MapVersion version;
    private final long seq;
    private final Map<Integer, Integer> frozenCosts;

    public SnapshotView(String snapshotId, MapVersion version, long seq, Map<Integer, Integer> frozenCosts) {
        this.snapshotId = snapshotId;
        this.version = version;
        this.seq = seq;
        this.frozenCosts = frozenCosts;
    }

    @Override
    public String versionId() {
        return version.versionId();
    }

    @Override
    public String snapshotId() {
        return snapshotId;
    }

    @Override
    public long seq() {
        return seq;
    }

    @Override
    public int costAt(int x, int y) {
        int idx = y * version.width() + x;
        Integer frozen = frozenCosts.get(idx);
        return frozen != null ? frozen : version.staticInflated()[idx] & 0xFF;
    }
}
