package com.example.starter.perception.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机合法路径纯单元测试。
 */
class StateMachineTest {

    @Test
    void allowsDeclaredTransitions() {
        assertTrue(StateMachine.canTransit(TrackState.CREATED, TrackState.TRACKING));
        assertTrue(StateMachine.canTransit(TrackState.CREATED, TrackState.OCCLUDED));
        assertTrue(StateMachine.canTransit(TrackState.TRACKING, TrackState.OCCLUDED));
        assertTrue(StateMachine.canTransit(TrackState.TRACKING, TrackState.TRACKING));
        assertTrue(StateMachine.canTransit(TrackState.OCCLUDED, TrackState.TRACKING));
        assertTrue(StateMachine.canTransit(TrackState.OCCLUDED, TrackState.LOST));
        assertTrue(StateMachine.canTransit(TrackState.LOST, TrackState.REIDENTIFIED));
        assertTrue(StateMachine.canTransit(TrackState.LOST, TrackState.RECYCLED));
        assertTrue(StateMachine.canTransit(TrackState.REIDENTIFIED, TrackState.TRACKING));
        assertTrue(StateMachine.canTransit(TrackState.REIDENTIFIED, TrackState.OCCLUDED));
    }

    @Test
    void rejectsIllegalTransitions() {
        assertFalse(StateMachine.canTransit(TrackState.CREATED, TrackState.LOST));
        assertFalse(StateMachine.canTransit(TrackState.TRACKING, TrackState.LOST));
        assertFalse(StateMachine.canTransit(TrackState.TRACKING, TrackState.RECYCLED));
        assertFalse(StateMachine.canTransit(TrackState.OCCLUDED, TrackState.RECYCLED));
        assertFalse(StateMachine.canTransit(TrackState.LOST, TrackState.TRACKING));
        assertFalse(StateMachine.canTransit(TrackState.REIDENTIFIED, TrackState.LOST));
        assertFalse(StateMachine.canTransit(TrackState.REIDENTIFIED, TrackState.RECYCLED));
        // RECYCLED 是终态，不得再迁出。
        assertFalse(StateMachine.canTransit(TrackState.RECYCLED, TrackState.TRACKING));
        assertFalse(StateMachine.canTransit(TrackState.RECYCLED, TrackState.RECYCLED));
    }

    @Test
    void requireTransitThrowsOnIllegalPath() {
        assertThrows(IllegalStateException.class,
                () -> StateMachine.requireTransit(TrackState.TRACKING, TrackState.RECYCLED));
        StateMachine.requireTransit(TrackState.LOST, TrackState.RECYCLED);
    }
}
