package com.example.starter.crane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.crane.AbstractCraneIntegrationTest;
import com.example.starter.crane.api.AuthorizationRequest;
import com.example.starter.crane.api.PoseAcceptance;
import com.example.starter.crane.api.PoseRequest;
import com.example.starter.crane.api.ReleaseRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 位姿判罚主流程与边界：授权内不预警、未授权预警、3 秒制动、制动幂等、
 * 位姿去重、乱序归位、恰结束时刻口径、释放竞态。
 */
class PoseProcessingServiceTest extends AbstractCraneIntegrationTest {

    private static final Instant W0 = Instant.parse("2026-09-25T10:00:00Z");

    @Autowired
    private AuthorizationService authorizationService;
    @Autowired
    private PoseProcessingService poseProcessingService;

    private PoseRequest pose(String crane, String ts, String zone) {
        return new PoseRequest(crane, Instant.parse(ts), zone, null, null, null);
    }

    private void grant(String requestId, String crane, String start, String end, String load) {
        authorizationService.apply(new AuthorizationRequest(requestId, crane, "Z-A1",
                Instant.parse(start), Instant.parse(end), new BigDecimal(load)));
    }

    private List<Map<String, Object>> warnings(String crane) {
        return jdbc.queryForList(
                "SELECT * FROM intrusion_warning WHERE crane_code = ? ORDER BY start_ts, id", crane);
    }

    private List<Map<String, Object>> brakes(String crane) {
        return jdbc.queryForList(
                "SELECT * FROM brake_command WHERE crane_code = ? ORDER BY id", crane);
    }

    @Test
    void authorized_pose_inside_window_no_warning() {
        grant("PA-1", "C-101", "2026-09-25T10:00:00Z", "2026-09-25T10:10:00Z", "1000");
        PoseAcceptance a = poseProcessingService.ingest(pose("C-101", "2026-09-25T10:01:00Z", "Z-A1"));
        assertThat(a.duplicated()).isFalse();
        assertThat(a.warningsGenerated()).isEmpty();
        assertThat(warnings("C-101")).isEmpty();
        assertThat(brakes("C-101")).isEmpty();
    }

    @Test
    void unauthorized_pose_generates_warning_then_exit_clears_no_brake() {
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:00Z", "Z-A1"));
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:01Z", "Z-A1"));
        // 第 2 秒退出 → CLEARED，不制动
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:02Z", null));

        List<Map<String, Object>> ws = warnings("C-101");
        assertThat(ws).hasSize(1);
        assertThat(ws.get(0).get("STATUS")).isEqualTo("CLEARED");
        assertThat(ws.get(0).get("BRAKE_ID")).isNull();
        assertThat(brakes("C-101")).isEmpty();
    }

    @Test
    void brake_after_three_seconds_persistent_intrusion() {
        PoseAcceptance a0 = poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:00Z", "Z-A1"));
        assertThat(a0.warningsGenerated()).hasSize(1);
        assertThat(a0.brakesIssued()).isEmpty();

        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:01Z", "Z-A1"));
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:02Z", "Z-A1"));
        // 持续 3 秒（第 4 个连续越限点）→ 制动
        PoseAcceptance a3 = poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:03Z", "Z-A1"));
        assertThat(a3.warningsGenerated()).isEmpty();
        assertThat(a3.brakesIssued()).hasSize(1);

        List<Map<String, Object>> ws = warnings("C-101");
        assertThat(ws).hasSize(1);
        assertThat(ws.get(0).get("STATUS")).isEqualTo("BRAKED");
        assertThat(ws.get(0).get("BRAKE_ID")).isNotNull();
        List<Map<String, Object>> bs = brakes("C-101");
        assertThat(bs).hasSize(1);
        assertThat(bs.get(0).get("COMMAND_CODE")).isEqualTo("BRK-" + ws.get(0).get("WARNING_CODE"));

        // 制动后退出 → CLOSED_AFTER_BRAKE
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:04Z", null));
        assertThat(warnings("C-101").get(0).get("STATUS")).isEqualTo("CLOSED_AFTER_BRAKE");
    }

    @Test
    void brake_is_idempotent_for_same_event() {
        for (int i = 0; i <= 3; i++) {
            poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:0" + i + "Z", "Z-A1"));
        }
        // 继续在区内上报：同一事件不得重复制动
        for (int i = 4; i <= 8; i++) {
            PoseAcceptance a = poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:0" + i + "Z", "Z-A1"));
            assertThat(a.brakesIssued()).as("第 %d 秒重复触发", i).isEmpty();
        }
        assertThat(brakes("C-101")).hasSize(1);
        assertThat(warnings("C-101")).hasSize(1);
    }

    @Test
    void duplicate_reading_deduplicated_and_does_not_retrigger() {
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:00Z", "Z-A1"));
        PoseAcceptance dup = poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:00Z", "Z-A1"));
        assertThat(dup.duplicated()).isTrue();
        assertThat(dup.warningsGenerated()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM pose_reading WHERE crane_code = 'C-101'", Integer.class)).isEqualTo(1);
        assertThat(warnings("C-101")).hasSize(1);
    }

    @Test
    void out_of_order_readings_rebased_by_timestamp() {
        // 先到 02、03 秒（越限，持续未达 3 秒）
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:02Z", "Z-A1"));
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:03Z", "Z-A1"));
        assertThat(warnings("C-101").get(0).get("START_TS")).isEqualTo(java.sql.Timestamp.from(W0.plusSeconds(2)));

        // 乱序补到 00、01 秒：补 00 秒后归位起点前移到 00，跨度 00→03 已达 3 秒，当次即补制动
        PoseAcceptance late0 = poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:00Z", "Z-A1"));
        PoseAcceptance late1 = poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:01Z", "Z-A1"));
        assertThat(late0.brakesIssued()).as("乱序归位后跨度达到 3 秒应补制动").hasSize(1);
        assertThat(late1.brakesIssued()).as("制动已下发，后续点不重复制动").isEmpty();

        List<Map<String, Object>> ws = warnings("C-101");
        assertThat(ws).hasSize(1);
        assertThat(ws.get(0).get("START_TS")).isEqualTo(java.sql.Timestamp.from(W0));
        assertThat(ws.get(0).get("STATUS")).isEqualTo("BRAKED");
        assertThat(brakes("C-101")).hasSize(1);
        // 起点前移留痕
        Integer corrections = jdbc.queryForObject(
                "SELECT COUNT(1) FROM event_correction WHERE warning_id = ?",
                Integer.class, ws.get(0).get("ID"));
        assertThat(corrections).isGreaterThanOrEqualTo(1);
    }

    @Test
    void pose_exactly_at_end_time_treated_as_authorized() {
        Instant end = Instant.parse("2026-09-25T10:05:00Z");
        grant("PE-1", "C-101", "2026-09-25T10:00:00Z", "2026-09-25T10:05:00Z", "1000");

        // 恰结束时刻点：授权内，不预警
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:05:00Z", "Z-A1"));
        assertThat(warnings("C-101")).as("end_time 时刻点按授权内处理").isEmpty();

        // 结束后 1 秒：越限
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:05:01Z", "Z-A1"));
        List<Map<String, Object>> ws = warnings("C-101");
        assertThat(ws).hasSize(1);
        assertThat(ws.get(0).get("START_TS")).isEqualTo(java.sql.Timestamp.from(end.plusSeconds(1)));
    }

    @Test
    void release_completed_concurrent_then_historical_legal_job_not_punished() {
        grant("PR-1", "C-101", "2026-09-25T10:00:00Z", "2026-09-25T10:10:00Z", "1000");
        // 作业完成并释放
        authorizationService.release("PR-1", new ReleaseRequest("COMPLETED"));

        // 释放后，窗口内历史位姿仍按合法处理，不判罚
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:09:59Z", "Z-A1"));
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:10:00Z", "Z-A1"));
        assertThat(warnings("C-101")).as("完成释放不倒罚已结束的合法作业").isEmpty();

        // 窗口之外仍判越限
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:10:01Z", "Z-A1"));
        assertThat(warnings("C-101")).hasSize(1);
    }

    @Test
    void cancel_release_revokes_after_release_time() {
        // 授权窗口覆盖固定时钟时刻 08:00:00Z（取消时刻）
        grant("PC-1", "C-101", "2026-09-25T07:00:00Z", "2026-09-25T09:00:00Z", "1000");
        authorizationService.release("PC-1", new ReleaseRequest("CANCELLED"));
        // 取消时刻（含）之前的窗口内位姿保留合法性
        poseProcessingService.ingest(pose("C-101", "2026-09-25T08:00:00Z", "Z-A1"));
        assertThat(warnings("C-101")).as("取消时刻（含）之前的位姿保留合法性").isEmpty();

        // 取消时刻之后进入：越限（即便仍处于声明窗口内）
        poseProcessingService.ingest(pose("C-101", "2026-09-25T08:00:01Z", "Z-A1"));
        assertThat(warnings("C-101")).hasSize(1);
    }

    @Test
    void zone_break_splits_into_two_warnings() {
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:00Z", "Z-A1"));
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:01Z", null));
        poseProcessingService.ingest(pose("C-101", "2026-09-25T10:00:02Z", "Z-A1"));
        List<Map<String, Object>> ws = warnings("C-101");
        assertThat(ws).hasSize(2);
        assertThat(ws.get(0).get("STATUS")).isEqualTo("CLEARED");
        assertThat(ws.get(1).get("STATUS")).isEqualTo("ACTIVE");
    }
}
