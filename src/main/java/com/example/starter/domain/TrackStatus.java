package com.example.starter.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 轨迹生命周期状态。
 *
 * <ul>
 *   <li>CREATED — 轨迹由首个检测创建，尚未形成稳定跟踪；</li>
 *   <li>TRACKING — 正在稳定跟踪；</li>
 *   <li>OCCLUDED — 短时遮挡，暂无可关联检测但身份保留；</li>
 *   <li>LOST — 长时间无观测，身份暂时丢失；</li>
 *   <li>REIDENTIFIED — 丢失后被重新识别（瞬时态，随即回到 TRACKING）；</li>
 *   <li>RECYCLED — 轨迹号回收的终态（包括 ID 切换后的旧身份）。</li>
 * </ul>
 */
public enum TrackStatus {
    CREATED,
    TRACKING,
    OCCLUDED,
    LOST,
    REIDENTIFIED,
    RECYCLED;

    /** 合法状态迁移表；未列出的迁移一律拒绝，状态不得非法跳转。 */
    private static final Map<TrackStatus, Set<TrackStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(TRACKING, RECYCLED),
            TRACKING, EnumSet.of(OCCLUDED, LOST, RECYCLED),
            OCCLUDED, EnumSet.of(TRACKING, LOST, RECYCLED),
            LOST, EnumSet.of(REIDENTIFIED, RECYCLED),
            REIDENTIFIED, EnumSet.of(TRACKING, RECYCLED),
            RECYCLED, EnumSet.noneOf(TrackStatus.class));

    public boolean canTransitionTo(TrackStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return this == RECYCLED;
    }

    /** 是否可以直接接收检测观测（TRACKING/OCCLUDED/CREATED 可在单步内完成观测）。 */
    public boolean isObservable() {
        return this == CREATED || this == TRACKING || this == OCCLUDED;
    }
}
