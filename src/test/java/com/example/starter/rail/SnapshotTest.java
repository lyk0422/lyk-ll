package com.example.starter.rail;

import com.example.starter.rail.dto.CreateRequestRequest;
import com.example.starter.rail.dto.SnapshotResponse;
import com.example.starter.rail.dto.TransitionRequest;
import com.example.starter.rail.model.PlanSnapshot;
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
 * 计划快照：发布后内容不可变，申请后续状态变化不得改写快照。
 */
class SnapshotTest extends BaseIntegrationTest {

    @Autowired
    private PossessionService service;

    @Autowired
    private PossessionRepository repository;

    @Test
    void snapshot_is_immutable_after_later_state_changes() {
        service.create(new CreateRequestRequest("R1", List.of("S1", "S2"),
                Instant.ofEpochMilli(at(10_000)), Instant.ofEpochMilli(at(20_000)),
                500, 700, 3));
        service.approve("R1");
        // 在 APPROVED/version=1 时发布快照。
        SnapshotResponse first = service.publishSnapshot("R1", "SNAP-1");
        assertThat(first.statusAtPublish()).isEqualTo("APPROVED");
        assertThat(first.versionAtPublish()).isOne();
        assertThat(first.content().get("status").asText()).isEqualTo("APPROVED");
        assertThat(first.content().get("sectionIds").get(0).asText()).isEqualTo("S1");
        assertThat(first.content().get("events").size()).isEqualTo(2);

        // 申请继续演进：激活并完成。
        service.activate("R1", new TransitionRequest(1, Instant.ofEpochMilli(at(12_000))), null);
        service.complete("R1", new TransitionRequest(2, Instant.ofEpochMilli(at(18_000))), null);

        SnapshotResponse reread = service.getSnapshot("SNAP-1");
        // 快照内容、版本、状态与事件数全部停留在发布时刻。
        assertThat(reread.versionAtPublish()).isOne();
        assertThat(reread.statusAtPublish()).isEqualTo("APPROVED");
        assertThat(reread.content().get("status").asText()).isEqualTo("APPROVED");
        assertThat(reread.content().get("version").asInt()).isOne();
        assertThat(reread.content().get("events").size()).isEqualTo(2);

        // 直接核对库中存储行也未被改写。
        PlanSnapshot stored = repository.findSnapshot("SNAP-1");
        assertThat(stored.getStatusAtPublish().name()).isEqualTo("APPROVED");
        assertThat(stored.getContent()).contains("\"status\":\"APPROVED\"");
    }

    @Test
    void duplicate_snapshot_id_is_rejected() {
        service.create(new CreateRequestRequest("R1", List.of("S1"),
                Instant.ofEpochMilli(at(10_000)), Instant.ofEpochMilli(at(20_000)),
                0, 0, 1));
        service.publishSnapshot("R1", "SNAP-DUP");
        assertThatThrownBy(() -> service.publishSnapshot("R1", "SNAP-DUP"))
                .isInstanceOf(ConflictException.class);
    }
}
