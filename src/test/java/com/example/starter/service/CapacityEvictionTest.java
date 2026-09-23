package com.example.starter.service;

import com.example.starter.domain.AuditType;
import com.example.starter.domain.PolicyType;
import com.example.starter.domain.RetentionPolicy;
import com.example.starter.dto.IngestResponse;
import com.example.starter.po.AuditRecord;
import com.example.starter.po.DeviceMessage;
import com.example.starter.testsupport.ForwarderTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局容量耗尽：先驱逐最低优先级、最旧且非 in-flight 的记录；
 * 若存活记录全部 in-flight，则拒绝新消息并记录原因。
 */
@TestPropertySource(properties = "forwarder.capacity=3")
class CapacityEvictionTest extends ForwarderTestBase {

    @BeforeEach
    void goOffline() {
        service.setOnline(false);
    }

    @Test
    void evictsLowestPriorityOldestPendingFirst() {
        service.ingest("dev-1", "/t", 1, 9, "hi-1", null);
        service.ingest("dev-1", "/t", 2, 1, "lo-1", null);
        service.ingest("dev-1", "/t", 3, 5, "mid-1", null);

        // 容量已满（3），写入第 4 条：应驱逐最低优先级 lo-1
        IngestResponse response = service.ingest("dev-2", "/t", 1, 5, "mid-2", null);

        assertThat(response.outcome()).isEqualTo("ACCEPTED");
        assertThat(messageRepository.findAll()).extracting(DeviceMessage::dedupKey)
                .containsExactlyInAnyOrder("hi-1", "mid-1", "mid-2");
        List<AuditRecord> evictions = auditRepository.findByType(AuditType.EVICT);
        assertThat(evictions).hasSize(1);
        assertThat(evictions.get(0).dedupKey()).isEqualTo("lo-1");
        assertThat(evictions.get(0).reason()).contains("lowest-priority");
    }

    @Test
    void rejectsWhenAllAliveMessagesAreInFlight() {
        // 离线写满 3 条 PENDING，然后在线把它们全部发送成 IN_FLIGHT
        service.ingest("dev-1", "/t", 1, 5, "f-1", null);
        service.ingest("dev-1", "/t", 2, 5, "f-2", null);
        service.ingest("dev-1", "/t", 3, 5, "f-3", null);
        service.setOnline(true);
        service.flush();
        assertThat(messageRepository.findAll()).allSatisfy(m ->
                assertThat(m.status()).isEqualTo("IN_FLIGHT"));

        // 容量满且没有可驱逐（非 in-flight）记录：新消息被拒绝
        IngestResponse rejected = service.ingest("dev-2", "/t", 1, 9, "blocked", null);

        assertThat(rejected.outcome()).isEqualTo("REJECTED");
        assertThat(messageRepository.findAll()).hasSize(3);
        List<AuditRecord> rejects = auditRepository.findByType(AuditType.REJECT);
        assertThat(rejects).hasSize(1);
        assertThat(rejects.get(0).dedupKey()).isEqualTo("blocked");
        assertThat(rejects.get(0).reason()).contains("capacity");
        assertThat(auditRepository.findByType(AuditType.EVICT)).isEmpty();
    }

    @Test
    void inFlightMessagesAreNeverEvicted() {
        // 在线时第一条立即 in-flight（高优先级）；离线再塞两条占满容量
        service.ingest("dev-1", "/t", 1, 9, "inflight-hi", null);
        service.setOnline(false);
        service.ingest("dev-1", "/t", 2, 1, "lo-pending", null);
        service.ingest("dev-1", "/t", 3, 1, "lo-pending-2", null);
        assertThat(messageRepository.countAll()).isEqualTo(3);

        // 新消息只能驱逐 PENDING，in-flight 高优先级记录不受影响
        IngestResponse response = service.ingest("dev-2", "/t", 1, 1, "new-low", null);

        assertThat(response.outcome()).isEqualTo("ACCEPTED");
        assertThat(messageRepository.findByDedupKey("inflight-hi")).isPresent();
        assertThat(messageRepository.findAll()).hasSize(3);
        AuditRecord eviction = auditRepository.findByType(AuditType.EVICT).get(0);
        assertThat(eviction.dedupKey()).isEqualTo("lo-pending");
    }
}
