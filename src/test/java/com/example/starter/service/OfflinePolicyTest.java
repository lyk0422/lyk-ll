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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 断网期间四种 Topic 保留策略：DROP / LATEST / FIFO / BOUNDED。 */
class OfflinePolicyTest extends ForwarderTestBase {

    @BeforeEach
    void goOffline() {
        service.setOnline(false);
    }

    @Test
    void dropPolicyDiscardsAndAudits() {
        service.configureTopic("/cmd", RetentionPolicy.of(PolicyType.DROP));

        IngestResponse response = service.ingest("dev-1", "/cmd", 1, 5, "drop-1", "hello");

        assertThat(response.outcome()).isEqualTo("DROPPED");
        assertThat(messageRepository.findAll()).isEmpty();
        List<AuditRecord> drops = auditRepository.findByType(AuditType.DROP_POLICY);
        assertThat(drops).hasSize(1);
        assertThat(drops.get(0).dedupKey()).isEqualTo("drop-1");
    }

    @Test
    void latestPolicyKeepsOnlyNewestAndAuditsOverwrite() {
        service.configureTopic("/state", RetentionPolicy.of(PolicyType.LATEST));

        service.ingest("dev-1", "/state", 1, 5, "latest-1", "v1");
        IngestResponse second = service.ingest("dev-1", "/state", 2, 5, "latest-2", "v2");

        assertThat(second.outcome()).isEqualTo("ACCEPTED");
        List<DeviceMessage> remaining = messageRepository.findAll();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).dedupKey()).isEqualTo("latest-2");
        List<AuditRecord> overwrites = auditRepository.findByType(AuditType.OVERWRITE);
        assertThat(overwrites).hasSize(1);
        assertThat(overwrites.get(0).dedupKey()).isEqualTo("latest-1");
        assertThat(overwrites.get(0).messageId()).isNotNull();
    }

    @Test
    void fifoPolicyPreservesFullOrder() {
        service.configureTopic("/log", RetentionPolicy.of(PolicyType.FIFO));

        for (int i = 1; i <= 5; i++) {
            IngestResponse r = service.ingest("dev-1", "/log", i, 1, "fifo-" + i, "p" + i);
            assertThat(r.outcome()).isEqualTo("ACCEPTED");
        }

        assertThat(messageRepository.findAll()).hasSize(5);
        assertThat(auditRepository.findByType(AuditType.DROP_POLICY)).isEmpty();
        assertThat(auditRepository.findByType(AuditType.OVERWRITE)).isEmpty();
    }

    @Test
    void boundedPolicyEvictsOldestWithinTopicOnly() {
        service.configureTopic("/b", RetentionPolicy.bounded(2));
        service.configureTopic("/other", RetentionPolicy.bounded(10));

        service.ingest("dev-1", "/b", 1, 5, "b-1", "x");
        service.ingest("dev-1", "/b", 2, 5, "b-2", "x");
        service.ingest("dev-1", "/b", 3, 5, "b-3", "x");
        service.ingest("dev-9", "/other", 1, 5, "o-1", "x");

        List<String> keys = messageRepository.findAll().stream()
                .map(DeviceMessage::dedupKey).sorted().toList();
        assertThat(keys).containsExactly("b-2", "b-3", "o-1");

        List<AuditRecord> overflow = auditRepository.findByType(AuditType.BOUNDED_OVERFLOW);
        assertThat(overflow).hasSize(1);
        assertThat(overflow.get(0).dedupKey()).isEqualTo("b-1");
    }

    @Test
    void unconfiguredTopicDefaultsToFifoWhenOffline() {
        IngestResponse response = service.ingest("dev-1", "/unknown", 1, 5, "u-1", "x");

        assertThat(response.outcome()).isEqualTo("ACCEPTED");
        assertThat(messageRepository.findAll()).hasSize(1);
    }
}
