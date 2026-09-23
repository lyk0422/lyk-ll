package com.example.starter.service;

import com.example.starter.domain.AuditType;
import com.example.starter.dto.AckResponse;
import com.example.starter.testsupport.ForwarderTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** ACK 语义：精确按 dedupKey 确认，可重复、乱序到达；超时 in-flight 可重试。 */
class AckTest extends ForwarderTestBase {

    @BeforeEach
    void seedInFlight() {
        // 三条在线写入立即进入 in-flight：低序号到高序号
        service.ingest("dev-A", "/t", 1, 5, "seq-1", null);
        service.ingest("dev-A", "/t", 2, 5, "seq-2", null);
        service.ingest("dev-A", "/t", 3, 5, "seq-3", null);
        upstream.resetStats();
    }

    @Test
    void explicitAckDeletesOnlyTargetMessage() {
        AckResponse ack = service.acknowledge("seq-2");

        assertThat(ack.outcome()).isEqualTo("DELIVERED");
        assertThat(messageRepository.findByDedupKey("seq-2")).isEmpty();
        // 其余消息仍在，in-flight 状态不变
        assertThat(messageRepository.findByDedupKey("seq-1")).isPresent();
        assertThat(messageRepository.findByDedupKey("seq-3")).isPresent();
        assertThat(service.status().delivered()).isEqualTo(1);
    }

    @Test
    void duplicateAckDoesNotChangeCounts() {
        assertThat(service.acknowledge("seq-1").outcome()).isEqualTo("DELIVERED");
        AckResponse duplicate = service.acknowledge("seq-1");

        assertThat(duplicate.outcome()).isEqualTo("IGNORED");
        assertThat(service.status().delivered()).isEqualTo(1);
        assertThat(auditRepository.findByType(AuditType.ACK_IGNORED)).hasSize(1);
    }

    @Test
    void higherSeqAckDoesNotRemoveLowerUnackedMessages() {
        // 先确认较大序号 3，较小序号 1、2 不得被误删
        AckResponse ack3 = service.acknowledge("seq-3");
        AckResponse ackUnknown = service.acknowledge("seq-3");

        assertThat(ack3.outcome()).isEqualTo("DELIVERED");
        assertThat(ackUnknown.outcome()).isEqualTo("IGNORED");
        assertThat(messageRepository.findAll()).extracting(m -> m.dedupKey())
                .containsExactlyInAnyOrder("seq-1", "seq-2");
        assertThat(service.status().delivered()).isEqualTo(1);
    }

    @Test
    void outOfOrderAcksDeliverIndependently() {
        service.acknowledge("seq-3");
        service.acknowledge("seq-1");
        service.acknowledge("seq-2");

        assertThat(messageRepository.findAll()).isEmpty();
        assertThat(service.status().delivered()).isEqualTo(3);
    }

    @Test
    void timedOutInFlightIsRetriedAndAckAfterRetryStillDelivers() {
        // 推进时钟超过 in-flight 超时窗口
        clock.advance(Duration.ofMillis(30_001));

        int sent = service.flush();
        assertThat(sent).isEqualTo(3);
        assertThat(auditRepository.findByType(AuditType.RETRY_RESET)).hasSize(3);
        // 每条消息第二次发送，attempts 已累加为 2
        assertThat(messageRepository.findByDedupKey("seq-1").orElseThrow().attempts())
                .isEqualTo(2);

        // 迟到的 ACK 仍按 dedupKey 精确确认
        assertThat(service.acknowledge("seq-1").outcome()).isEqualTo("DELIVERED");
        assertThat(messageRepository.findByDedupKey("seq-1")).isEmpty();
        assertThat(service.status().delivered()).isEqualTo(1);
    }
}
