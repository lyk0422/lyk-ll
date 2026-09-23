package com.example.starter.rail;

import com.example.starter.rail.dto.CreateRequestRequest;
import com.example.starter.rail.dto.RequestResponse;
import com.example.starter.rail.dto.TransitionRequest;
import com.example.starter.rail.model.RequestEvent;
import com.example.starter.rail.model.RequestStatus;
import com.example.starter.rail.repo.PossessionRepository;
import com.example.starter.rail.service.PossessionService;
import com.example.starter.rail.web.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖激活/完成/取消合法迁移、版本校验、终态幂等（同参返回首次结果不追加事件、
 * 异参 409）与请求键复用规则。
 */
class TransitionTest extends BaseIntegrationTest {

    @Autowired
    private PossessionService service;

    @Autowired
    private PossessionRepository repository;

    private void createAndApprove(String id) {
        service.create(new CreateRequestRequest(id, List.of("S1"),
                Instant.ofEpochMilli(at(10_000)), Instant.ofEpochMilli(at(20_000)),
                0, 0, 1));
        service.approve(id);
    }

    private TransitionRequest body(int version, long businessTimeOffset) {
        return new TransitionRequest(version, Instant.ofEpochMilli(at(businessTimeOffset)));
    }

    @Test
    void activate_complete_lifecycle_increments_versions_and_appends_events() {
        createAndApprove("R1");

        RequestResponse active = service.activate("R1", body(1, 12_000), null);
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(active.version()).isEqualTo(2);

        RequestResponse completed = service.complete("R1", body(2, 18_000), null);
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.version()).isEqualTo(3);

        assertThat(repository.findEvents("R1"))
                .extracting(RequestEvent::getEventType)
                .containsExactly("SUBMITTED", "APPROVED", "ACTIVATED", "COMPLETED");
        RequestEvent activated = repository.findEvents("R1").stream()
                .filter(e -> e.getEventType().equals("ACTIVATED")).findFirst().orElseThrow();
        assertThat(activated.getBusinessTime()).isEqualTo(at(12_000));
    }

    @Test
    void illegal_migrations_are_rejected() {
        service.create(new CreateRequestRequest("R1", List.of("S1"),
                Instant.ofEpochMilli(at(10_000)), Instant.ofEpochMilli(at(20_000)), 0, 0, 1));
        // PENDING 不能激活。
        assertThatThrownBy(() -> service.activate("R1", body(0, 12_000), null))
                .isInstanceOf(ConflictException.class);
        service.approve("R1");
        // APPROVED 不能直接完成。
        assertThatThrownBy(() -> service.complete("R1", body(1, 18_000), null))
                .isInstanceOf(ConflictException.class);

        service.activate("R1", body(1, 12_000), null);
        service.complete("R1", body(2, 18_000), null);
        // COMPLETED 终态不能再激活或取消。
        assertThatThrownBy(() -> service.activate("R1", body(3, 19_000), null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.cancel("R1", body(3, 19_000), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void version_mismatch_is_rejected_without_state_change() {
        createAndApprove("R1");
        assertThatThrownBy(() -> service.activate("R1", body(9, 12_000), null))
                .isInstanceOf(ConflictException.class);
        assertThat(repository.findRequest("R1").getStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(repository.findRequest("R1").getVersion()).isOne();
    }

    @Test
    void terminal_repeat_with_same_parameters_returns_first_result_without_extra_event() {
        createAndApprove("R1");
        service.activate("R1", body(1, 12_000), null);
        RequestResponse first = service.complete("R1", body(2, 18_000), null);
        int eventCount = repository.findEvents("R1").size();

        // 同参（expectedVersion/businessTime 相同）重复完成：返回首次结果，不追加事件。
        RequestResponse replay = service.complete("R1", body(2, 18_000), null);
        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(repository.findEvents("R1")).hasSize(eventCount);
        assertThat(repository.findRequest("R1").getStatus()).isEqualTo(RequestStatus.COMPLETED);
    }

    @Test
    void terminal_repeat_with_different_parameters_is_conflict() {
        createAndApprove("R1");
        service.activate("R1", body(1, 12_000), null);
        service.complete("R1", body(2, 18_000), null);

        // 不同业务时间即异参 -> 409。
        assertThatThrownBy(() -> service.complete("R1", body(2, 18_500), null))
                .isInstanceOf(ConflictException.class);
        // 不同 expectedVersion 同样异参 -> 409。
        assertThatThrownBy(() -> service.complete("R1", body(3, 18_000), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void idempotency_key_same_parameters_replays_first_result() {
        createAndApprove("R1");
        RequestResponse first = service.activate("R1", body(1, 12_000), "KEY-ACT-1");
        RequestResponse replay = service.activate("R1", body(1, 12_000), "KEY-ACT-1");

        assertThat(replay.status()).isEqualTo(first.status());
        assertThat(replay.version()).isEqualTo(first.version());
        // 即使申请已不是 APPROVED，重放也只返回首次结果，且只激活一次。
        assertThat(repository.findEvents("R1"))
                .filteredOn(e -> e.getEventType().equals("ACTIVATED"))
                .hasSize(1);
    }

    @Test
    void idempotency_key_reused_with_different_parameters_is_conflict() {
        createAndApprove("R1");
        service.activate("R1", body(1, 12_000), "KEY-ACT-2");
        // 同键但业务时间不同 -> 指纹不同 -> 409。
        assertThatThrownBy(() -> service.activate("R1", body(1, 13_000), "KEY-ACT-2"))
                .isInstanceOf(ConflictException.class);
        // 同键但 expectedVersion 不同 -> 409。
        assertThatThrownBy(() -> service.activate("R1", body(2, 12_000), "KEY-ACT-2"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void cancel_from_approved_is_terminal_and_version_checked() {
        createAndApprove("R1");
        RequestResponse cancelled = service.cancel("R1", body(1, 15_000), "KEY-CAN-1");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.version()).isEqualTo(2);
        assertThat(repository.findRequest("R1").getStatus()).isEqualTo(RequestStatus.CANCELLED);

        // 同参取消幂等返回首次结果。
        RequestResponse replay = service.cancel("R1", body(1, 15_000), "KEY-CAN-1");
        assertThat(replay.status()).isEqualTo("CANCELLED");
        assertThat(repository.findEvents("R1"))
                .filteredOn(e -> e.getEventType().equals("CANCELLED"))
                .hasSize(1);
    }
}
