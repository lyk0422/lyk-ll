package com.example.starter.perception.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 轨迹状态机：集中声明并校验合法迁移路径，任何非法迁移必须快速失败而不是落库。
 *
 * <p>合法路径：
 * <ul>
 *   <li>CREATED -&gt; TRACKING（建轨后再次观测）/ OCCLUDED（仅单帧后超时）</li>
 *   <li>TRACKING -&gt; OCCLUDED（超过遮挡间隔无观测）</li>
 *   <li>OCCLUDED -&gt; TRACKING（短时遮挡内重现）/ LOST（遮挡持续超时）</li>
 *   <li>LOST -&gt; REIDENTIFIED（重识别门限内重现）/ RECYCLED（显式回收）</li>
 *   <li>REIDENTIFIED -&gt; TRACKING（重识别确认后的后续观测）/ OCCLUDED（再次失联超时）</li>
 * </ul>
 */
public final class StateMachine {

    private static final Map<TrackState, Set<TrackState>> ALLOWED = new EnumMap<>(TrackState.class);

    static {
        ALLOWED.put(TrackState.CREATED, EnumSet.of(TrackState.TRACKING, TrackState.OCCLUDED));
        ALLOWED.put(TrackState.TRACKING, EnumSet.of(TrackState.OCCLUDED, TrackState.TRACKING));
        ALLOWED.put(TrackState.OCCLUDED, EnumSet.of(TrackState.TRACKING, TrackState.LOST));
        ALLOWED.put(TrackState.LOST, EnumSet.of(TrackState.REIDENTIFIED, TrackState.RECYCLED));
        ALLOWED.put(TrackState.REIDENTIFIED, EnumSet.of(TrackState.TRACKING, TrackState.OCCLUDED));
        ALLOWED.put(TrackState.RECYCLED, EnumSet.noneOf(TrackState.class));
    }

    private StateMachine() {
    }

    /** 迁移是否合法（允许 TRACKING 等自环，表示一次有效关联但状态不变）。 */
    public static boolean canTransit(TrackState from, TrackState to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(TrackState.class)).contains(to);
    }

    /** 校验迁移路径，非法时抛出 IllegalArgumentException（由服务层转 422）。 */
    public static void requireTransit(TrackState from, TrackState to) {
        if (!canTransit(from, to)) {
            throw new IllegalStateException("illegal track state transition: " + from + " -> " + to);
        }
    }
}
