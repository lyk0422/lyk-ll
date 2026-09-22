package com.example.starter.ros.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LifecycleStateTest {

    @Test
    void legalAdjacentTransitions() {
        assertThat(LifecycleState.UNCONFIGURED.canTransitionTo(LifecycleState.INACTIVE)).isTrue();
        assertThat(LifecycleState.INACTIVE.canTransitionTo(LifecycleState.ACTIVE)).isTrue();
        assertThat(LifecycleState.ACTIVE.canTransitionTo(LifecycleState.INACTIVE)).isTrue();
        assertThat(LifecycleState.INACTIVE.canTransitionTo(LifecycleState.UNCONFIGURED)).isTrue();
    }

    @Test
    void anyMainStateCanShutdownToFinalized() {
        assertThat(LifecycleState.UNCONFIGURED.canTransitionTo(LifecycleState.FINALIZED)).isTrue();
        assertThat(LifecycleState.INACTIVE.canTransitionTo(LifecycleState.FINALIZED)).isTrue();
        assertThat(LifecycleState.ACTIVE.canTransitionTo(LifecycleState.FINALIZED)).isTrue();
    }

    @Test
    void illegalNonAdjacentTransitionsRejected() {
        assertThat(LifecycleState.UNCONFIGURED.canTransitionTo(LifecycleState.ACTIVE)).isFalse();
        assertThat(LifecycleState.ACTIVE.canTransitionTo(LifecycleState.UNCONFIGURED)).isFalse();
        assertThat(LifecycleState.ACTIVE.canTransitionTo(LifecycleState.FINALIZED)).isTrue();
    }

    @Test
    void finalizedNodeCannotTransitionAnywhere() {
        for (LifecycleState target : LifecycleState.values()) {
            assertThat(LifecycleState.FINALIZED.canTransitionTo(target)).isFalse();
        }
        assertThat(LifecycleState.FINALIZED.isFinal()).isTrue();
    }

    @Test
    void valueOfSafeIsCaseInsensitiveAndReturnsNullForGarbage() {
        assertThat(LifecycleState.valueOfSafe("active")).isEqualTo(LifecycleState.ACTIVE);
        assertThat(LifecycleState.valueOfSafe(" INACTIVE ")).isEqualTo(LifecycleState.INACTIVE);
        assertThat(LifecycleState.valueOfSafe("RUNNING")).isNull();
        assertThat(LifecycleState.valueOfSafe(null)).isNull();
    }
}
