package com.example.starter.rail;

import com.example.starter.rail.dto.CreateRequestRequest;
import com.example.starter.rail.dto.RequestResponse;
import com.example.starter.rail.model.ConflictDetail;
import com.example.starter.rail.model.PossessionRequest;
import com.example.starter.rail.model.RequestStatus;
import com.example.starter.rail.repo.PossessionRepository;
import com.example.starter.rail.service.PossessionService;
import com.example.starter.rail.web.ApprovalConflictException;
import com.example.starter.rail.web.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖：普通审批主流程、半开端点相接不冲突、缓冲冲突、多区段原子审批、
 * 冲突明细排序、重复审批/状态校验。
 */
class ApprovalTest extends BaseIntegrationTest {

    @Autowired
    private PossessionService service;

    @Autowired
    private PossessionRepository repository;

    private CreateRequestRequest request(String id, List<String> sections,
                                         long start, long end, long bufBefore, long bufAfter,
                                         int priority) {
        return new CreateRequestRequest(id, sections, Instant.ofEpochMilli(start),
                Instant.ofEpochMilli(end), bufBefore, bufAfter, priority);
    }

    @Test
    void normal_approval_marks_all_sections_approved_atomically() {
        service.create(request("R1", List.of("S1", "S2"), at(10_000), at(20_000), 0, 0, 1));

        RequestResponse response = service.approve("R1");

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.version()).isOne();
        assertThat(response.sectionIds()).containsExactly("S1", "S2");
        assertThat(repository.findEvents("R1"))
                .extracting(e -> e.getEventType())
                .containsExactly("SUBMITTED", "APPROVED");
    }

    @Test
    void half_open_endpoints_touching_do_not_conflict() {
        // R1 占用 [10000,20000)，R2 紧接 [20000,30000)，端点相接无正长度重叠。
        service.create(request("R1", List.of("S1"), at(10_000), at(20_000), 0, 0, 1));
        service.approve("R1");
        service.create(request("R2", List.of("S1"), at(20_000), at(30_000), 0, 0, 1));

        assertThat(service.queryConflicts("R2")).isEmpty();
        assertThat(service.approve("R2").status()).isEqualTo("APPROVED");
    }

    @Test
    void positive_overlap_is_rejected_with_sorted_conflict_details() {
        service.create(request("R1", List.of("S2", "S1"), at(10_000), at(30_000), 0, 0, 1));
        service.approve("R1");
        // R2 在两个区段上与 R1 正长度重叠 5000ms。
        service.create(request("R2", List.of("S1", "S2"), at(25_000), at(40_000), 0, 0, 1));

        List<ConflictDetail> conflicts = service.queryConflicts("R2");
        assertThat(conflicts).hasSize(2);
        // 按区段排序：S1 在 S2 之前。
        assertThat(conflicts).extracting(ConflictDetail::getSectionId)
                .containsExactly("S1", "S2");
        assertThat(conflicts).allSatisfy(c -> {
            assertThat(c.getOtherRequestId()).isEqualTo("R1");
            assertThat(c.getOverlapMs()).isEqualTo(5_000L);
            assertThat(c.getOtherStatus()).isEqualTo(RequestStatus.APPROVED);
        });

        assertThatThrownBy(() -> service.approve("R2"))
                .isInstanceOf(ApprovalConflictException.class)
                .satisfies(ex -> {
                    ApprovalConflictException ace = (ApprovalConflictException) ex;
                    assertThat(ace.getConflicts()).hasSize(2);
                });
        // 整笔拒绝：R2 仍为 PENDING，未追加 APPROVED 事件。
        assertThat(repository.findRequest("R2").getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(repository.findEvents("R2"))
                .extracting(e -> e.getEventType())
                .containsExactly("SUBMITTED");
    }

    @Test
    void safety_buffers_participate_in_conflict_detection() {
        // R1 窗 [10000,20000) 带后缓冲 3000，实际占用到 23000。
        service.create(request("R1", List.of("S1"), at(10_000), at(20_000), 0, 3_000, 1));
        service.approve("R1");
        // R2 裸窗 [22000,30000)：与 R1 裸窗不重叠，但落在后缓冲内 -> 冲突。
        service.create(request("R2", List.of("S1"), at(22_000), at(30_000), 0, 0, 1));
        assertThat(service.queryConflicts("R2")).hasSize(1);
        assertThatThrownBy(() -> service.approve("R2"))
                .isInstanceOf(ApprovalConflictException.class);

        // R3 窗起点恰为 R1 缓冲后边界 23000，端点相接 -> 不冲突。
        service.create(request("R3", List.of("S1"), at(23_000), at(30_000), 0, 0, 1));
        assertThat(service.queryConflicts("R3")).isEmpty();
        assertThat(service.approve("R3").status()).isEqualTo("APPROVED");
    }

    @Test
    void conflict_on_one_section_rejects_all_sections_atomically() {
        service.create(request("R1", List.of("S1"), at(10_000), at(20_000), 0, 0, 1));
        service.approve("R1");
        // R2 涉及 S1（冲突）与 S3（空闲），整笔不得部分批准。
        service.create(request("R2", List.of("S1", "S3"), at(10_000), at(20_000), 0, 0, 1));
        assertThatThrownBy(() -> service.approve("R2"))
                .isInstanceOf(ApprovalConflictException.class);

        PossessionRequest r2 = repository.findRequest("R2");
        assertThat(r2.getStatus()).isEqualTo(RequestStatus.PENDING);
        // 空闲区段 S3 上不能因 R2 留下任何占用：再来一个只占 S3 的申请应能通过。
        service.create(request("R3", List.of("S3"), at(10_000), at(20_000), 0, 0, 1));
        assertThat(service.approve("R3").status()).isEqualTo("APPROVED");
    }

    @Test
    void cancelling_approved_window_frees_section_for_new_approval() {
        service.create(request("R1", List.of("S1"), at(10_000), at(20_000), 0, 0, 1));
        service.approve("R1");
        service.create(request("R2", List.of("S1"), at(10_000), at(20_000), 0, 0, 1));
        assertThat(service.queryConflicts("R2")).hasSize(1);

        // R1 取消后释放占用，R2 可审批。
        service.cancel("R1", new com.example.starter.rail.dto.TransitionRequest(
                1, Instant.ofEpochMilli(at(15_000))), null);
        assertThat(service.queryConflicts("R2")).isEmpty();
        assertThat(service.approve("R2").status()).isEqualTo("APPROVED");
    }

    @Test
    void approving_non_pending_request_is_rejected() {
        service.create(request("R1", List.of("S1"), at(10_000), at(20_000), 0, 0, 1));
        service.approve("R1");
        assertThatThrownBy(() -> service.approve("R1"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void creating_invalid_window_or_duplicate_id_is_rejected() {
        service.create(request("R1", List.of("S1"), at(10_000), at(20_000), 0, 0, 1));
        // end <= start 非法。
        assertThatThrownBy(() -> service.create(
                request("R2", List.of("S1"), at(20_000), at(10_000), 0, 0, 1)))
                .isInstanceOf(ConflictException.class);
        // 区段重复。
        assertThatThrownBy(() -> service.create(
                request("R3", List.of("S1", "S1"), at(10_000), at(20_000), 0, 0, 1)))
                .isInstanceOf(ConflictException.class);
        // requestId 重复。
        assertThatThrownBy(() -> service.create(
                request("R1", List.of("S2"), at(10_000), at(20_000), 0, 0, 1)))
                .isInstanceOf(ConflictException.class);
    }
}
