package com.example.starter.costmap.view;

import com.example.starter.costmap.model.MapVersion;

import java.util.Map;

/**
 * 实时视图：以静态膨胀层为底，被增量重算过的格点使用动态缓存值。
 */
public class LiveView implements CostView {

    private final MapVersion version;
    private final Map<Integer, Integer> dynamicCosts;

    public LiveView(MapVersion version, Map<Integer, Integer> dynamicCosts) {
        this.version = version;
        this.dynamicCosts = dynamicCosts;
    }

    @Override
    public String versionId() {
        return version.versionId();
    }

    @Override
    public String snapshotId() {
        return null;
    }

    @Override
    public long seq() {
        return version.seq();
    }

    @Override
    public int costAt(int x, int y) {
        int idx = y * version.width() + x;
        Integer dynamic = dynamicCosts.get(idx);
        return dynamic != null ? dynamic : version.staticInflated()[idx] & 0xFF;
    }
}
