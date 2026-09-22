package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轨迹状态机合法迁移路径单元测试。
 */
class TrackStatusTest {

    @Test
    void legalPaths() {
        assertTrue(TrackStatus.CREATED.canTransitionTo(TrackStatus.TRACKING));
        assertTrue(TrackStatus.CREATED.canTransitionTo(TrackStatus.RECYCLED));
        assertTrue(TrackStatus.TRACKING.canTransitionTo(TrackStatus.OCCLUDED));
        assertTrue(TrackStatus.TRACKING.canTransitionTo(TrackStatus.LOST));
        assertTrue(TrackStatus.OCCLUDED.canTransitionTo(TrackStatus.TRACKING));
        assertTrue(TrackStatus.OCCLUDED.canTransitionTo(TrackStatus.LOST));
        assertTrue(TrackStatus.LOST.canTransitionTo(TrackStatus.REIDENTIFIED));
        assertTrue(TrackStatus.LOST.canTransitionTo(TrackStatus.RECYCLED));
        assertTrue(TrackStatus.REIDENTIFIED.canTransitionTo(TrackStatus.TRACKING));
    }

    @Test
    void illegalPathsRejected() {
        // 不允许跳跃或回退式非法迁移。
        assertFalse(TrackStatus.CREATED.canTransitionTo(TrackStatus.LOST));
        assertFalse(TrackStatus.CREATED.canTransitionTo(TrackStatus.OCCLUDED));
        assertFalse(TrackStatus.TRACKING.canTransitionTo(TrackStatus.CREATED));
        assertFalse(TrackStatus.TRACKING.canTransitionTo(TrackStatus.REIDENTIFIED));
        assertFalse(TrackStatus.OCCLUDED.canTransitionTo(TrackStatus.CREATED));
        assertFalse(TrackStatus.LOST.canTransitionTo(TrackStatus.TRACKING));
        assertFalse(TrackStatus.LOST.canTransitionTo(TrackStatus.OCCLUDED));
        assertFalse(TrackStatus.REIDENTIFIED.canTransitionTo(TrackStatus.LOST));
    }

    @Test
    void recycledIsTerminalAndImmutable() {
        assertTrue(TrackStatus.RECYCLED.isTerminal());
        for (TrackStatus s : TrackStatus.values()) {
            assertFalse(TrackStatus.RECYCLED.canTransitionTo(s),
                    "RECYCLED 不能迁移到 " + s);
        }
        assertFalse(TrackStatus.RECYCLED.isObservable());
        assertTrue(TrackStatus.TRACKING.isObservable());
        assertTrue(TrackStatus.OCCLUDED.isObservable());
    }
}
