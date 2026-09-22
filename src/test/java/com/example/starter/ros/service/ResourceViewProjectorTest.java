package com.example.starter.ros.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.ros.domain.EventType;
import com.example.starter.ros.domain.NodeEvent;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResourceViewProjectorTest {

    private ResourceViewProjector projector;

    @BeforeEach
    void setUp() {
        projector = new ResourceViewProjector();
    }

    private NodeEvent event(long revision, String nodeId, EventType type, String payload) {
        return new NodeEvent(revision, nodeId, type, payload, false, LocalDateTime.now());
    }

    private String registeredPayload(String state) {
        return "{\"state\":\"" + state + "\",\"resources\":["
                + "{\"category\":\"TOPIC\",\"fqName\":\"/ns/t\",\"interfaceType\":\"std_msgs/msg/String\"}]}";
    }

    @Test
    void appliesEventsInRevisionOrder() {
        assertThat(projector.apply(event(1, "n1", EventType.REGISTERED,
                registeredPayload("UNCONFIGURED")))).isTrue();
        assertThat(projector.apply(event(2, "n1", EventType.STATE_CHANGED,
                "{\"fromState\":\"UNCONFIGURED\",\"toState\":\"INACTIVE\"}"))).isTrue();
        assertThat(projector.lastAppliedRevision()).isEqualTo(2L);
        assertThat(projector.nodeState("n1")).isEqualTo("INACTIVE");
        assertThat(projector.resourcesOf("n1")).hasSize(1);
    }

    @Test
    void duplicateEventIsIgnoredAndViewUnchanged() {
        projector.apply(event(1, "n1", EventType.REGISTERED,
                registeredPayload("UNCONFIGURED")));
        projector.apply(event(2, "n1", EventType.STATE_CHANGED,
                "{\"fromState\":\"UNCONFIGURED\",\"toState\":\"INACTIVE\"}"));
        // 重复投递 revision=1 的旧事件(内容为注册),不得把状态回退
        boolean applied = projector.apply(event(1, "n1", EventType.REGISTERED,
                registeredPayload("UNCONFIGURED")));
        assertThat(applied).isFalse();
        assertThat(projector.lastAppliedRevision()).isEqualTo(2L);
        assertThat(projector.nodeState("n1")).isEqualTo("INACTIVE");
        assertThat(projector.skippedRevisions()).containsExactly(1L);
    }

    @Test
    void outOfOrderOlderEventDoesNotRollBackView() {
        // 先收到高 revision 的注销,再收到低 revision 的状态变更
        projector.apply(event(1, "n1", EventType.REGISTERED,
                registeredPayload("UNCONFIGURED")));
        projector.apply(event(3, "n1", EventType.DEREGISTERED, "{}"));
        assertThat(projector.knownNodeIds()).doesNotContain("n1");

        boolean applied = projector.apply(event(2, "n1", EventType.STATE_CHANGED,
                "{\"toState\":\"ACTIVE\"}"));
        assertThat(applied).isFalse();
        assertThat(projector.knownNodeIds()).doesNotContain("n1");
        assertThat(projector.lastAppliedRevision()).isEqualTo(3L);
    }

    @Test
    void laterEventAfterSkippedOnesStillApplies() {
        projector.apply(event(5, "n1", EventType.REGISTERED,
                registeredPayload("UNCONFIGURED")));
        assertThat(projector.apply(event(4, "n1", EventType.STATE_CHANGED,
                "{\"toState\":\"ACTIVE\"}"))).isFalse();
        assertThat(projector.apply(event(6, "n1", EventType.STATE_CHANGED,
                "{\"toState\":\"INACTIVE\"}"))).isTrue();
        assertThat(projector.nodeState("n1")).isEqualTo("INACTIVE");
        assertThat(projector.skippedRevisions()).isEqualTo(List.of(4L));
    }
}
