package com.example.starter.pump.business;

import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.ArchiveRequest;
import com.example.starter.pump.web.dto.LevelIngestRequest;
import com.example.starter.pump.web.dto.PumpCommandRequest;
import com.example.starter.pump.web.dto.ReplayResult;
import com.example.starter.pump.web.dto.WarningRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端合成示例：橙色预警启动 → 多站同时刻联动启泵（闸门到位）→ 水位复测
 * 恰达目标自动停泵 → 另一站恰满30分钟失联派单 → 复盘归档 → 归档后迟到数据只记迟到 → 台账复算。
 */
class EndToEndScenarioTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private PumpdownFacade facade;

    @Test
    void orangeWarning_linkedStart_recheck_missContact_archive_late_replay() {
        // 1) 橙色预警启动预降（分区目标位取最严格的 2.500）
        var warning = facade.handleWarning(new WarningRequest("e2e-warn", "ZN", "ORANGE", T0));
        Long id = warning.pumpdownId();
        assertThat(warning.triggered()).isTrue();

        // 2) 同一分区同时刻两台泵联动启泵：归入同一套序列，闸门按站点配置到位
        var s1 = facade.handlePumpCommand(new PumpCommandRequest(
                "e2e-start-1", id, "PS-01", "START", T0, null));
        var s2 = facade.handlePumpCommand(new PumpCommandRequest(
                "e2e-start-2", id, "PS-02", "START", T0, null));
        assertThat(s2.sequenceId()).isEqualTo(s1.sequenceId());
        assertThat(s1.gateOpening()).isEqualByComparingTo("80.00");
        assertThat(s2.gateOpening()).isEqualByComparingTo("75.00");

        // 3) PS-01 水位复测：高于目标不停，恰达目标位 2.500 自动停泵
        facade.handleLevel(new LevelIngestRequest("PS-01", T0.plusMinutes(5), new BigDecimal("3.200")));
        var atTarget = facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusMinutes(10), new BigDecimal("2.500")));
        assertThat(atTarget.autoStopTaskId()).isNotNull();
        assertThat(atTarget.autoStopReason()).isEqualTo("TARGET_REACHED");

        // 4) PS-02 末次数据在 t=0（按启泵时刻无更早数据，以 t=0 为基准），恰满30分钟上送 → 失联派单
        var miss = facade.handleLevel(new LevelIngestRequest(
                "PS-02", T0.plusMinutes(30), new BigDecimal("3.000")));
        assertThat(miss.missContactOrderId()).isNotNull();
        assertThat(miss.missContactTriggeredAt()).isEqualTo(T0.plusMinutes(30));

        // 5) 复盘归档（终态）
        var archived = facade.handleArchive(new ArchiveRequest("e2e-arch", id, T0.plusHours(2)));
        assertThat(archived.status()).isEqualTo("ARCHIVED");

        // 6) 归档后迟到液位：只记迟到事件，不入曲线
        clock.setNow(T0.plusHours(3));
        var late = facade.handleLevel(new LevelIngestRequest(
                "PS-01", T0.plusHours(3), new BigDecimal("2.100")));
        assertThat(late.late()).isTrue();

        // 7) 台账复算：曲线 3 条（PS-01×2 + PS-02×1），任务 4 条（2 START + 自动STOP + …），失联 1 单
        ReplayResult replay = facade.replay(id);
        assertThat(replay.status()).isEqualTo("ARCHIVED");
        assertThat(replay.levelCurve()).hasSize(3);
        assertThat(replay.tasks()).hasSize(3); // PS-01 START, PS-02 START, PS-01 自动 STOP
        assertThat(replay.missContactOrders()).hasSize(1);
        assertThat(replay.tasks()).extracting(ReplayResult.TaskItem::gateOpening)
                .filteredOn(opening -> opening != null).hasSize(2);
    }
}
