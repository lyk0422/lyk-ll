package com.example.starter.service;

import com.example.starter.domain.AuditType;
import com.example.starter.dto.IngestResponse;
import com.example.starter.testsupport.ForwarderTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 相同 dedupKey 幂等：只保留一条待发记录。 */
class DedupTest extends ForwarderTestBase {

    @BeforeEach
    void goOffline() {
        service.setOnline(false);
    }

    @Test
    void sameDedupKeyIsAcceptedOnlyOnce() {
        IngestResponse first = service.ingest("dev-1", "/t", 1, 5, "dup-key", "a");
        IngestResponse second = service.ingest("dev-1", "/t", 2, 5, "dup-key", "b");

        assertThat(first.outcome()).isEqualTo("ACCEPTED");
        assertThat(second.outcome()).isEqualTo("DEDUP");
        assertThat(messageRepository.findAll()).hasSize(1);
        assertThat(messageRepository.findByDedupKey("dup-key")).isPresent();
        assertThat(auditRepository.findByType(AuditType.DEDUP_SKIP)).hasSize(1);
    }

    @Test
    void sameDedupKeyWhileInFlightIsAlsoDeduplicated() {
        // 在线写入会立刻进入 in-flight
        IngestResponse first = service.ingest("dev-1", "/t", 1, 5, "inflight-key", "a");
        IngestResponse second = service.ingest("dev-1", "/t", 2, 5, "inflight-key", "b");

        assertThat(first.outcome()).isEqualTo("ACCEPTED");
        assertThat(second.outcome()).isEqualTo("DEDUP");
        assertThat(messageRepository.findAll()).hasSize(1);
    }

    @Test
    void redeliveredDedupKeyAfterAckIsDeduplicatedPersistently() {
        // 在线写入 → ACK 完成 → 设备以相同全局 dedupKey 再次写入（生产者重试）
        assertThat(service.ingest("dev-1", "/t", 1, 5, "acked-key", "a").outcome())
                .isEqualTo("ACCEPTED");
        assertThat(service.acknowledge("acked-key").outcome()).isEqualTo("DELIVERED");
        assertThat(messageRepository.findAll()).isEmpty();

        IngestResponse retry = service.ingest("dev-1", "/t", 1, 5, "acked-key", "a");

        assertThat(retry.outcome()).isEqualTo("DEDUP");
        assertThat(messageRepository.findAll()).isEmpty();
        // 该判定来自持久化审计，重启后仍然成立（由 CrashRecoveryTest 的文件库保证）
        assertThat(auditRepository.findByType(AuditType.DEDUP_SKIP)).hasSize(1);
    }
}
