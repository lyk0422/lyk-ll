package com.example.starter.rail;

import com.example.starter.rail.dto.CreateRequestRequest;
import com.example.starter.rail.dto.RequestResponse;
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
 * 紧急抢占规则：必须能在同一事务内抢占全部冲突、尚未激活且严格低优先级的窗口；
 * 已激活、同级/更高优先级或只能部分抢占时整笔拒绝并回滚，不留部分抢占。
 */
class PreemptionTest extends BaseIntegrationTest {

    @Autowired
    private PossessionService service;

    @Autowired
    private PossessionRepository repository;

    private CreateRequestRequest request(String id, List<String> sections,
                                         long start, long end, int priority) {
        return new CreateRequestRequest(id, sections, Instant.ofEpochMilli(start),
                Instant.ofEpochMilli(end), 0, 0, priority);
    }

    @Test
    void emergency_preempts_all_lower_priority_approved_windows_in_one_transaction() {
        service.create(request("LOW1", List.of("S1"), at(10_000), at(30_000), 1));
        service.approve("LOW1");
        service.create(request("LOW2", List.of("S2"), at(20_000), at(40_000), 4));
        service.approve("LOW2");
        // 紧急申请优先级 10，严格高于两者，跨 S1/S2 重叠。
        service.create(request("EMG", List.of("S1", "S2"), at(15_000), at(35_000), 10));

        RequestResponse response = service.approve("EMG", PossessionService.ApprovalMode.PREEMPT);

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(repository.findRequest("LOW1").getStatus()).isEqualTo(RequestStatus.PREEMPTED);
        assertThat(repository.findRequest("LOW2").getStatus()).isEqualTo(RequestStatus.PREEMPTED);
        // 被抢占窗口版本递增，记录 PREEMPTED 事件并标明抢占方。
        assertThat(repository.findEvents("LOW1"))
                .extracting(e -> e.getEventType())
                .endsWith("PREEMPTED");
        assertThat(repository.findEvents("LOW1").get(2).getDetail()).contains("EMG");
        assertThat(repository.findRequest("EMG").getStatus()).isEqualTo(RequestStatus.APPROVED);
    }

    @Test
    void preemption_is_blocked_by_active_window_and_everything_rolls_back() {
        service.create(request("LOW", List.of("S1"), at(10_000), at(30_000), 1));
        service.approve("LOW");
        service.activate("LOW",
                new com.example.starter.rail.dto.TransitionRequest(1, Instant.ofEpochMilli(at(12_000))),
                null);
        service.create(request("EMG", List.of("S1"), at(15_000), at(35_000), 10));

        assertThatThrownBy(() -> service.approve("EMG", PossessionService.ApprovalMode.PREEMPT))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ACTIVE");

        // 整笔回滚：紧急申请仍 PENDING，已激活窗口保持 ACTIVE。
        assertThat(repository.findRequest("EMG").getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(repository.findRequest("LOW").getStatus()).isEqualTo(RequestStatus.ACTIVE);
        assertThat(repository.findEvents("LOW"))
                .extracting(e -> e.getEventType())
                .doesNotContain("PREEMPTED");
    }

    @Test
    void preemption_is_rejected_for_same_or_higher_priority() {
        service.create(request("PEER", List.of("S1"), at(10_000), at(30_000), 10));
        service.approve("PEER");
        service.create(request("EMG", List.of("S1"), at(15_000), at(35_000), 10));

        assertThatThrownBy(() -> service.approve("EMG", PossessionService.ApprovalMode.PREEMPT))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("priority");

        assertThat(repository.findRequest("EMG").getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(repository.findRequest("PEER").getStatus()).isEqualTo(RequestStatus.APPROVED);
    }

    @Test
    void partial_preemption_is_rejected_atomically() {
        // S1 上可抢占的低优先级窗口。
        service.create(request("LOW", List.of("S1"), at(10_000), at(30_000), 1));
        service.approve("LOW");
        // S2 上已激活窗口，无法抢占。
        service.create(request("ACT", List.of("S2"), at(10_000), at(30_000), 1));
        service.approve("ACT");
        service.activate("ACT",
                new com.example.starter.rail.dto.TransitionRequest(1, Instant.ofEpochMilli(at(12_000))),
                null);
        service.create(request("EMG", List.of("S1", "S2"), at(15_000), at(25_000), 10));

        assertThatThrownBy(() -> service.approve("EMG", PossessionService.ApprovalMode.PREEMPT))
                .isInstanceOf(ConflictException.class);

        // 不得发生部分抢占：LOW 仍是 APPROVED，EMG 仍是 PENDING。
        assertThat(repository.findRequest("LOW").getStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(repository.findRequest("EMG").getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(repository.findEvents("LOW"))
                .extracting(e -> e.getEventType())
                .doesNotContain("PREEMPTED");
    }

    @Test
    void preempted_windows_release_their_sections() {
        service.create(request("LOW", List.of("S1"), at(10_000), at(30_000), 1));
        service.approve("LOW");
        service.create(request("EMG", List.of("S1"), at(10_000), at(30_000), 10));
        service.approve("EMG", PossessionService.ApprovalMode.PREEMPT);

        // 又一个新申请落在同一区段同一时段：仅剩 EMG 占用，普通审批应报与 EMG 的冲突。
        service.create(request("NEW", List.of("S1"), at(10_000), at(30_000), 1));
        var conflicts = service.queryConflicts("NEW");
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).getOtherRequestId()).isEqualTo("EMG");
        // 被抢占窗口不再产生冲突。
        assertThat(conflicts).noneMatch(c -> c.getOtherRequestId().equals("LOW"));
    }
}
