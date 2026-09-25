package com.example.starter.pump.business;

import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.ArchiveRequest;
import com.example.starter.pump.web.dto.LevelIngestRequest;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.WarningRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复盘归档终态、重复归档幂等与台账复算。
 */
class ArchiveAndReplayTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private PumpdownFacade facade;

    @Test
    void archive_isTerminal_repeatedArchiveReplays_andLedgerRecomputes() {
        Long id = facade.handleWarning(new WarningRequest("w-a", "ZN", "ORANGE", T0)).pumpdownId();
        facade.handlePumpCommand(new PumpCommandRequest(
                "t-start", id, "PS-01", "START", T0, new BigDecimal("80.00")));
        facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusMinutes(10), new BigDecimal("2.500"))); // 恰达目标自动停泵

        var first = facade.handleArchive(new ArchiveRequest("a-1", id, T0.plusHours(1)));
        assertThat(first.status()).isEqualTo("ARCHIVED");
        assertThat(first.replayed()).isFalse();
        assertThat(first.summary()).contains("\"levelCurve\"").contains("\"tasks\"");

        // 重复归档（不同 requestId 但引用同一已终态事件）：返回幂等成功
        var second = facade.handleArchive(new ArchiveRequest("a-2", id, T0.plusHours(2)));
        assertThat(second.replayed()).isTrue();
        assertThat(second.archivedAt()).isEqualTo(first.archivedAt());
        assertThat(second.summary()).isEqualTo(first.summary());

        // 归档记录仅一条
        assertThat(count("review_archive")).isEqualTo(1);

        // 台账复算：1 条曲线、START+自动STOP 两条任务（START 带闸门开度）
        var replay = facade.replay(id);
        assertThat(replay.status()).isEqualTo("ARCHIVED");
        assertThat(replay.levelCurve()).hasSize(1);
        assertThat(replay.tasks()).hasSize(2);
        assertThat(replay.tasks().get(0).action()).isEqualTo("START");
        assertThat(replay.tasks().get(0).gateOpening()).isEqualByComparingTo("80.00");
        assertThat(replay.tasks().get(1).action()).isEqualTo("STOP");
        assertThat(replay.tasks().get(1).reason()).isEqualTo("TARGET_REACHED");
    }

    @Test
    void sameArchiveRequestId_replaysIdempotently() {
        Long id = facade.handleWarning(new WarningRequest("w-b", "ZN", "ORANGE", T0)).pumpdownId();
        var req = new ArchiveRequest("a-same", id, T0.plusHours(1));
        var first = facade.handleArchive(req);
        var replay = facade.handleArchive(req);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.archivedAt()).isEqualTo(first.archivedAt());
    }
}
