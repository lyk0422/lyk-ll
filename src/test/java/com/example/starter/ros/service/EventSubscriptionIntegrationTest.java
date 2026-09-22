package com.example.starter.ros.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.ros.AbstractIntegrationTest;
import com.example.starter.ros.TestDataCleaner;
import com.example.starter.ros.web.dto.EventBatchResponse;
import com.example.starter.ros.web.dto.NodeOperationRequest;
import com.example.starter.ros.web.dto.RegisterNodeRequest;
import com.example.starter.ros.web.dto.ResourceSpec;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 事件订阅水位测试:递增消费、确认后不重复、乱序确认不回退、订阅方相互独立。
 */
class EventSubscriptionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EventSubscriptionService eventService;
    @Autowired
    private NodeRegistryService registryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        TestDataCleaner.cleanAll(jdbcTemplate);
    }

    private void registerNode(String nodeId, String requestId) {
        registryService.register(new RegisterNodeRequest(nodeId, "/robot", "talker",
                List.of(new ResourceSpec("TOPIC", "chatter", "std_msgs/msg/String")),
                requestId));
    }

    @Test
    void pollReturnsEventsInAscendingRevisionAndAckAdvancesCursor() {
        registerNode("n1", "req-1");
        registerNode("n2", "req-2");

        EventBatchResponse first = eventService.poll("sub-1", 50);
        assertThat(first.lastRevision()).isZero();
        assertThat(first.events()).hasSize(2);
        assertThat(first.events().get(0).revision()).isLessThan(first.events().get(1).revision());
        assertThat(first.events().get(0).nodeId()).isEqualTo("n1");

        long maxRevision = first.events().get(1).revision();
        long newCursor = eventService.acknowledge("sub-1", maxRevision);
        assertThat(newCursor).isEqualTo(maxRevision);

        EventBatchResponse second = eventService.poll("sub-1", 50);
        assertThat(second.lastRevision()).isEqualTo(maxRevision);
        assertThat(second.events()).isEmpty();
    }

    @Test
    void outOfOrderAckNeverRollsCursorBack() {
        registerNode("n1", "req-1");
        registerNode("n2", "req-2");

        EventBatchResponse batch = eventService.poll("sub-1", 50);
        long low = batch.events().get(0).revision();
        long high = batch.events().get(1).revision();

        // 先确认高水位
        assertThat(eventService.acknowledge("sub-1", high)).isEqualTo(high);
        // 乱序补确认低水位,水位不得回退
        assertThat(eventService.acknowledge("sub-1", low)).isEqualTo(high);
        // 重复确认高水位,水位保持
        assertThat(eventService.acknowledge("sub-1", high)).isEqualTo(high);
    }

    @Test
    void eachSubscriberKeepsIndependentCursor() {
        registerNode("n1", "req-1");

        EventBatchResponse sub1 = eventService.poll("sub-1", 50);
        EventBatchResponse sub2 = eventService.poll("sub-2", 50);
        assertThat(sub1.events()).hasSize(1);
        assertThat(sub2.events()).hasSize(1);

        eventService.acknowledge("sub-1", sub1.events().get(0).revision());

        assertThat(eventService.poll("sub-1", 50).events()).isEmpty();
        assertThat(eventService.poll("sub-2", 50).events()).hasSize(1);
    }

    @Test
    void newlyPublishedEventsBecomeVisibleAfterCursor() {
        registerNode("n1", "req-1");
        EventBatchResponse batch = eventService.poll("sub-1", 50);
        eventService.acknowledge("sub-1", batch.events().get(0).revision());

        registryService.deregister(new NodeOperationRequest("n1", "req-2"));
        EventBatchResponse next = eventService.poll("sub-1", 50);
        assertThat(next.events()).hasSize(1);
        assertThat(next.events().get(0).eventType()).isEqualTo("DEREGISTERED");
    }
}
