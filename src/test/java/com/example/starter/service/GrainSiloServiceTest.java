package com.example.starter.service;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.FixedClockConfig;
import com.example.starter.db.SiloRepository;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.AlarmResponse;
import com.example.starter.web.dto.ArchiveResponse;
import com.example.starter.web.dto.ReadingAcceptResponse;
import com.example.starter.web.dto.RetestResponse;
import com.example.starter.web.dto.VentilationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static com.example.starter.TestDataFactory.alarm;
import static com.example.starter.TestDataFactory.archive;
import static com.example.starter.TestDataFactory.fan;
import static com.example.starter.TestDataFactory.retest;
import static com.example.starter.TestDataFactory.silo;
import static com.example.starter.TestDataFactory.ventilation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 粮情测控主流程与边界规则测试（真实 H2 事务，不 mock 数据访问）。
 */
@Import(FixedClockConfig.class)
class GrainSiloServiceTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 9, 0, 0);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 9, 26, 12, 0, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 9, 26, 14, 0, 0);
    private static final LocalDateTime T4 = LocalDateTime.of(2026, 9, 26, 16, 0, 0);

    @Autowired
    private GrainSiloService service;

    @Autowired
    private SiloRepository siloRepository;

    // ------------------------------------------------------------------
    // 安全温度边界
    // ------------------------------------------------------------------

    @Test
    void exact_safe_temperature_is_normal_not_alarm() {
        service.createSilo(silo("S1", 20.0, 2.0, 70.0));

        AlarmResponse response = service.reportAlarm(alarm("a-1", "S1", T0, 19.5, 20.0, 18.0));

        assertFalse(response.alarmed(), "恰等安全温度必须判正常");
        assertEquals(20.0, response.maxTemperature(), 0.0001);
        assertEquals("MONITORING", response.state());
    }

    @Test
    void any_point_above_safe_temperature_alarms() {
        service.createSilo(silo("S1", 20.0, 2.0, 70.0));

        AlarmResponse response = service.reportAlarm(alarm("a-2", "S1", T0, 19.0, 20.1, 18.0));

        assertTrue(response.alarmed(), "任一点严格高于安全温度必须告警");
        assertEquals("ALARM", response.state());
    }

    @Test
    void alarm_state_is_not_cleared_by_one_normal_reading() {
        service.createSilo(silo("S1", 20.0, 2.0, 70.0));
        service.reportAlarm(alarm("a-2a", "S1", T0, 23.0));

        // 再来一次全部正常的读数：状态不自行回退，须走通风/复测流程解除
        AlarmResponse response = service.reportAlarm(alarm("a-2b", "S1", T0.plusMinutes(5), 18.0, 19.0));

        assertFalse(response.alarmed(), "本次读数不超标");
        assertEquals("ALARM", response.state(), "流转严格单向，单次正常读数不清除告警态");
    }

    @Test
    void ventilation_requires_alarm_state_first() {
        service.createSilo(silo("S1", 20.0, 2.0, 70.0));
        service.createFan(fan("F1"));
        // 未发生告警直接通风：422，且不产生作业窗
        ApiException ex = assertThrows(ApiException.class, () -> service.orderVentilation(
                ventilation("v-pre", "S1", "F1", 60.0, T1, T2)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertTrue(service.windows("S1").isEmpty());
    }

    // ------------------------------------------------------------------
    // 平衡湿度边界禁通风
    // ------------------------------------------------------------------

    @Test
    void ambient_humidity_above_equilibrium_forbids_ventilation() {
        prepareAlarm("S1", "F1", "a-3", 22.0);

        ApiException ex = assertThrows(ApiException.class, () -> service.orderVentilation(
                ventilation("v-3", "S1", "F1", 70.5, T1, T2)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertTrue(ex.getMessage().contains("禁止通风"));
        // 禁通风不改变任何作业窗
        assertTrue(service.windows("S1").isEmpty());
    }

    @Test
    void ambient_humidity_equal_equilibrium_allows_ventilation() {
        prepareAlarm("S1", "F1", "a-4", 22.0);

        VentilationResponse response = service.orderVentilation(
                ventilation("v-4", "S1", "F1", 70.0, T1, T2));

        assertEquals(1, response.roundNo());
        assertEquals("EXECUTING", response.status());
    }

    // ------------------------------------------------------------------
    // 两轮通风上限
    // ------------------------------------------------------------------

    @Test
    void two_rounds_limit_then_manual_handling() {
        prepareAlarm("S1", "F1", "a-5", 23.0);

        // 一轮通风后复测仍超标（有超温点且梯度 3.0 > 2.0）
        service.orderVentilation(ventilation("v-5-1", "S1", "F1", 60.0, T1, T2));
        RetestResponse r1 = service.retest(retest("rt-5-1", "S1", T2, 22.5, 19.0));
        assertFalse(r1.passed());
        assertEquals("SECOND_VENTILATION", r1.nextAction());
        assertEquals("RETEST_FAIL", r1.state());

        // 二次通风允许
        service.orderVentilation(ventilation("v-5-2", "S1", "F1", 60.0, T3, T4));
        RetestResponse r2 = service.retest(retest("rt-5-2", "S1", T4, 22.0, 18.5));
        assertFalse(r2.passed());
        assertEquals("MANUAL", r2.nextAction());
        assertEquals("MANUAL", r2.state());

        // 两轮之后禁止第三次通风
        ApiException ex = assertThrows(ApiException.class, () -> service.orderVentilation(
                ventilation("v-5-3", "S1", "F1", 60.0,
                        LocalDateTime.of(2026, 9, 27, 9, 0),
                        LocalDateTime.of(2026, 9, 27, 11, 0))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertTrue(ex.getMessage().contains("人工处置"));
    }

    @Test
    void retest_gradient_equal_limit_passes() {
        prepareAlarm("S1", "F1", "a-6", 23.0);
        service.orderVentilation(ventilation("v-6", "S1", "F1", 60.0, T1, T2));

        // 最高 21.0、最低 19.0：无点超 20 安全温？21 > 20 会超温，因此构造全部 ≤ 20，梯度恰等 2.0
        RetestResponse response = service.retest(retest("rt-6", "S1", T2, 20.0, 18.0));

        assertTrue(response.passed(), "最高温恰等安全温度且梯度恰等上限，必须判通过");
        assertEquals(2.0, response.gradient(), 0.0001);
        assertEquals("PASS", response.nextAction());
        assertEquals("RETEST_PASS", response.state());
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void same_request_replays_first_result() {
        prepareAlarm("S1", "F1", "a-7", 23.0);
        var request = ventilation("v-7", "S1", "F1", 60.0, T1, T2);

        VentilationResponse first = service.orderVentilation(request);
        VentilationResponse replay = service.orderVentilation(request);

        assertEquals(first.operationId(), replay.operationId());
        assertEquals(first.roundNo(), replay.roundNo());
        assertEquals(1, service.windows("S1").size(), "重放不得新增作业窗");
        assertEquals(1, siloRepository.findByCode("S1").orElseThrow().ventilationRounds());
    }

    @Test
    void same_key_different_params_conflicts_without_window_change() {
        prepareAlarm("S1", "F1", "a-8", 23.0);
        service.orderVentilation(ventilation("v-8", "S1", "F1", 60.0, T1, T2));
        int windowsBefore = service.windows("S1").size();

        ApiException ex = assertThrows(ApiException.class, () -> service.orderVentilation(
                ventilation("v-8", "S1", "F1", 55.0, T1, T2)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(windowsBefore, service.windows("S1").size(), "同键异参返回 409 且不改变作业窗");
    }

    // ------------------------------------------------------------------
    // 归档终态与迟到数据
    // ------------------------------------------------------------------

    @Test
    void archive_is_terminal_and_double_archive_is_idempotent() {
        service.createSilo(silo("S1"));
        service.createFan(fan("F1"));

        ArchiveResponse first = service.archive(archive("ar-1", "S1"));
        assertTrue(first.archived());
        assertFalse(first.alreadyArchived());

        // 同键重放严格返回首次结果（幂等成功，终态一致）
        ArchiveResponse replay = service.archive(archive("ar-1", "S1"));
        assertTrue(replay.archived());
        assertFalse(replay.alreadyArchived(), "同键重放返回首次结果");
        assertEquals("ARCHIVED", replay.state());

        // 新的归档请求到达已归档终态：返回幂等成功并标记此前已归档
        ArchiveResponse again = service.archive(archive("ar-1-repeat", "S1"));
        assertTrue(again.archived());
        assertTrue(again.alreadyArchived(), "对已归档仓重复归档返回幂等成功");

        // 归档后再走通风令被拒绝（终态）
        ApiException ex = assertThrows(ApiException.class, () -> service.orderVentilation(
                ventilation("v-after", "S1", "F1", 60.0, T1, T2)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertTrue(ex.getMessage().contains("归档"));
    }

    @Test
    void late_readings_after_archive_are_recorded_as_events_only() {
        service.createSilo(silo("S1"));
        service.archive(archive("ar-2", "S1"));

        ReadingAcceptResponse response = service.uploadReadings("S1",
                new com.example.starter.web.dto.ReadingBatchRequest(
                        java.util.List.of(com.example.starter.TestDataFactory.point("P1", 30.0))));

        assertTrue(response.late());
        assertEquals("ARCHIVED", response.state());
        assertTrue(service.temperatureCurve("S1").isEmpty(), "迟到数据不得进入温度曲线");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_reading_event", Long.class));
    }

    @Test
    void archiving_during_executing_ventilation_is_rejected() {
        prepareAlarm("S1", "F1", "a-9", 23.0);
        service.orderVentilation(ventilation("v-9", "S1", "F1", 60.0, T1, T2));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.archive(archive("ar-9", "S1")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    // ------------------------------------------------------------------
    // 作业窗半开区间 [start,end)
    // ------------------------------------------------------------------

    @Test
    void adjacent_windows_touching_at_endpoint_do_not_conflict() {
        service.createFan(fan("F1"));
        service.createSilo(silo("S1", 20.0, 2.0, 70.0));
        service.reportAlarm(alarm("a-w1", "S1", T0, 18.0, 23.0));
        service.orderVentilation(ventilation("v-w1", "S1", "F1", 60.0, T1, T2));
        // 一轮复测通过：作业完成、风机释放
        service.retest(retest("rt-w1", "S1", T2, 20.0, 18.0));

        service.createSilo(silo("S2", 20.0, 2.0, 70.0));
        service.reportAlarm(alarm("a-w2", "S2", T0, 18.0, 23.0));
        // 新仓作业窗 [T2,T3) 与既有 [T1,T2) 在 T2 端点相接：半开区间下不冲突
        VentilationResponse response = service.orderVentilation(
                ventilation("v-w2", "S2", "F1", 60.0, T2, T3));

        assertEquals(1, service.windows("S1").size());
        assertEquals(1, service.windows("S2").size(), "端点相接不冲突，新作业窗应被受理");
        assertEquals(T2, response.startTime());
        assertEquals(T3, response.endTime());
    }

    @Test
    void overlapping_windows_conflict_and_create_no_window() {
        service.createFan(fan("F1"));
        service.createSilo(silo("S1", 20.0, 2.0, 70.0));
        service.reportAlarm(alarm("a-w3", "S1", T0, 18.0, 23.0));
        service.orderVentilation(ventilation("v-w3", "S1", "F1", 60.0, T1, T3));
        service.createSilo(silo("S2", 20.0, 2.0, 70.0));
        service.reportAlarm(alarm("a-w4", "S2", T0, 18.0, 23.0));

        // [T2,T4) 与既有 [T1,T3) 在 (T2,T3) 段真实重叠
        ApiException ex = assertThrows(ApiException.class, () -> service.orderVentilation(
                ventilation("v-w4", "S2", "F1", 60.0, T2, T4)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertTrue(service.windows("S2").isEmpty(), "冲突请求不得创建作业窗");
    }

    // ------------------------------------------------------------------
    // 可复算查询
    // ------------------------------------------------------------------

    @Test
    void curve_windows_and_retests_are_recomputable() {
        prepareAlarm("S1", "F1", "a-10", 23.0);
        service.orderVentilation(ventilation("v-10", "S1", "F1", 60.0, T1, T2));
        service.retest(retest("rt-10", "S1", T2, 20.0, 18.0));
        service.archive(archive("ar-10", "S1"));

        assertEquals(4, service.temperatureCurve("S1").size(),
                "告警采样 2 个测点 + 复测采样 2 个测点，逐点可复算");
        assertEquals(1, service.windows("S1").size());
        assertEquals(T1, service.windows("S1").get(0).startTime());
        assertEquals(T2, service.windows("S1").get(0).endTime());
        assertEquals(1, service.retests("S1").size());
        assertEquals(2.0, service.retests("S1").get(0).gradient(), 0.0001);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void prepareAlarm(String siloCode, String fanCode, String alarmRequestId, double hotTemp) {
        service.createSilo(silo(siloCode, 20.0, 2.0, 70.0));
        service.createFan(fan(fanCode));
        service.reportAlarm(alarm(alarmRequestId, siloCode, T0, 18.0, hotTemp));
    }
}
