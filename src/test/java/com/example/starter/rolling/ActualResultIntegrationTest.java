package com.example.starter.rolling;

import com.example.starter.rolling.api.dto.FreezeRequest;
import com.example.starter.rolling.api.dto.ScheduleView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 实绩回传：合同号＋道次去重、按道次归位换辊周期、恰达 10 公里锁定、冻结后迟到事件。
 */
class ActualResultIntegrationTest extends AbstractRollingIntegrationTest {

    @Test
    void 实绩按合同号与道次去重() {
        draftThenValidateThenIssue("req-s", "L1",
                items(item("C1", 1250, "3", true, 0), item("C2", 1200, "3", false, 1)));

        var pass1a = service.reportActual(actual("a-1", "L1", "C1", 1, "3"));
        var pass2 = service.reportActual(actual("a-2", "L1", "C1", 2, "3"));
        var pass1b = service.reportActual(actual("a-1-replay", "L1", "C1", 1, "3"));

        assertThat(pass1a.accepted()).isTrue();
        assertThat(pass2.accepted()).isTrue();
        assertThat(pass1b.accepted()).isFalse();
        assertThat(pass1b.rollingKm()).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM actual_result WHERE contract_no = 'C1'", Integer.class)).isEqualTo(2);
    }

    @Test
    void 实绩按道次归位到换辊周期并分别累计() {
        // C1/C2 在周期1，窗口 [02:00,03:00)，C3/C4 在周期2，宽度重新由宽到窄。
        var order = createRequest("req-camp", "L2", "U1", null, items(
                item("C1", 1250, "2", true, 0),
                item("C2", 1200, "2", false, 1),
                item("C3", 1250, "2", true, 3),
                item("C4", 1200, "2", true, 4)),
                List.of(window(2, 3)));
        ScheduleView view = service.createOrRevise(order);
        service.validate(view.id());
        service.issue(view.id(), new com.example.starter.rolling.api.dto.IssueRequest("req-camp-i"));

        var r1 = service.reportActual(actual("c-a1", "L2", "C1", 1, "6"));
        assertThat(r1.campaignNo()).isEqualTo(1);
        assertThat(r1.campaignTotalKm()).isEqualByComparingTo("6");
        assertThat(r1.locked()).isFalse();

        var r3 = service.reportActual(actual("c-a3", "L2", "C3", 1, "6"));
        assertThat(r3.campaignNo()).isEqualTo(2);
        assertThat(r3.campaignTotalKm()).isEqualByComparingTo("6");
        assertThat(r3.locked()).isFalse();

        var r4 = service.reportActual(actual("c-a4", "L2", "C4", 1, "4"));
        assertThat(r4.campaignNo()).isEqualTo(2);
        assertThat(r4.campaignTotalKm()).isEqualByComparingTo("10");
        assertThat(r4.locked()).isTrue();
        assertThat(r4.rollChangeRequired()).isTrue();
    }

    @Test
    void 累计里程恰达10公里即锁定后续排程并提示换辊() {
        draftThenValidateThenIssue("req-lock", "L3", items(
                item("C1", 1250, "4", true, 0),
                item("C2", 1200, "4", false, 1),
                item("C3", 1150, "4", false, 2)));

        var first = service.reportActual(actual("l-1", "L3", "C1", 1, "6"));
        assertThat(first.campaignTotalKm()).isEqualByComparingTo("6");
        assertThat(first.locked()).isFalse();

        var atThreshold = service.reportActual(actual("l-2", "L3", "C2", 1, "4"));
        assertThat(atThreshold.campaignTotalKm()).isEqualByComparingTo("10");
        assertThat(atThreshold.locked()).isTrue();
        assertThat(atThreshold.rollChangeRequired()).isTrue();

        // 恰达阈值的当前块不锁，其后所有作业锁定。
        assertThat(jdbc.queryForObject(
                "SELECT locked FROM schedule_item WHERE contract_no = 'C1'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT locked FROM schedule_item WHERE contract_no = 'C2'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT locked FROM schedule_item WHERE contract_no = 'C3'", Boolean.class)).isTrue();

        // 锁定状态保持，不静默放行。
        var more = service.reportActual(actual("l-3", "L3", "C1", 2, "0.5"));
        assertThat(more.locked()).isTrue();
        assertThat(more.rollChangeRequired()).isTrue();
    }

    @Test
    void 实绩超过10公里仍如实记录但必须锁定提示换辊() {
        draftThenValidateThenIssue("req-over", "L4", items(
                item("C1", 1250, "4", true, 0),
                item("C2", 1200, "4", false, 1)));

        service.reportActual(actual("o-1", "L4", "C1", 1, "6"));
        var over = service.reportActual(actual("o-2", "L4", "C2", 1, "4.001"));

        assertThat(over.campaignTotalKm()).isEqualByComparingTo("10.001");
        assertThat(over.locked()).isTrue();
        assertThat(over.rollChangeRequired()).isTrue();
    }

    @Test
    void 冻结后迟到实绩只登记事件不改冻结结论() {
        ScheduleView issued = draftThenValidateThenIssue("req-late", "L5", items(
                item("C1", 1250, "4", true, 0),
                item("C2", 1200, "4", false, 1)));
        // 冻结前累计仅 6 公里，未触发换辊阈值。
        service.reportActual(actual("lt-1", "L5", "C1", 1, "6"));
        service.freeze(issued.id(), new FreezeRequest("lt-fz"));

        // 迟到的 5 公里即使累计会超 10，也只登记事件：冻结里程与换辊结论保持冻结时状态。
        var late = service.reportActual(actual("lt-2", "L5", "C2", 1, "5", T0.plusDays(1)));

        assertThat(late.late()).isTrue();
        assertThat(late.accepted()).isFalse();
        assertThat(late.rollingKm()).isNull();
        assertThat(late.locked()).isFalse();
        assertThat(late.rollChangeRequired()).isFalse();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM actual_result WHERE order_id = ?",
                Integer.class, issued.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM late_actual_event WHERE order_id = ?",
                Integer.class, issued.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT rolling_km FROM late_actual_event WHERE contract_no = 'C2'",
                java.math.BigDecimal.class)).isEqualByComparingTo("5");

        // 冻结单状态与里程结论不变。
        ScheduleView stillFrozen = service.get(issued.id());
        assertThat(stillFrozen.status()).isEqualTo("FROZEN");
        assertThat(stillFrozen.locked()).isFalse();
        assertThat(stillFrozen.rollChangeRequired()).isFalse();
    }

    @Test
    void 迟到实绩同键重放幂等且不重复登记事件() {
        ScheduleView issued = draftThenValidateThenIssue("req-late2", "L6",
                items(item("C1", 1250, "4", true, 0)));
        service.freeze(issued.id(), new FreezeRequest("lt2-fz"));

        var req = actual("lt2-1", "L6", "C1", 1, "3");
        var first = service.reportActual(req);
        var replay = service.reportActual(req);

        assertThat(first.late()).isTrue();
        assertThat(replay.late()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM late_actual_event WHERE request_id = 'lt2-1'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void 合同不在生效版本中返回404() {
        draftThenValidateThenIssue("req-404", "L7",
                items(item("C1", 1250, "3", true, 0)));
        assertThatThrownBy(() -> service.reportActual(actual("x-1", "L7", "NOPE", 1, "1")))
                .isInstanceOf(com.example.starter.rolling.service.BusinessException.class);
    }
}
