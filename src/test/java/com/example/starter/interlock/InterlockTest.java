package com.example.starter.interlock;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * 五防闭锁测试：带负荷拉合刀闸、带电挂接地线、带地线送电直接闭锁拒绝；
 * 母线停电顺序违例整步拒绝。闭锁命中留痕可复算。
 */
class InterlockTest extends BaseIntegrationTest {

    @Test
    void 带负荷拉合刀闸_直接闭锁拒绝() {
        long orderId = draftReviewIssue("load-disc", "INT-1101",
                step(1, "DIS-1101", "OPEN_DISCONNECTOR", "OPEN"));

        var response = confirm(orderId, 1, "load-disc-c1", "操作人A", "监护人B");
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).contains("R_LOAD_DISCONNECTOR");

        // 整步拒绝：步骤仍为待执行，设备状态未变
        assertThat(stepStatus(orderId, 1)).isEqualTo("PENDING");
        assertThat(deviceState("DIS-1101")).isEqualTo("CLOSED");
        assertThat(count("interlock_hit",
                "order_id = ? AND rule_code = 'R_LOAD_DISCONNECTOR'", orderId)).isEqualTo(1);
    }

    @Test
    void 带电挂接地线_直接闭锁拒绝() {
        long orderId = draftReviewIssue("live-gnd", "INT-1101",
                step(1, "GW-1101", "ATTACH_GROUND", "ATTACHED"));

        var response = confirm(orderId, 1, "live-gnd-c1", "操作人A", "监护人B");
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).contains("R_LIVE_GROUND");

        assertThat(stepStatus(orderId, 1)).isEqualTo("PENDING");
        assertThat(deviceState("GW-1101")).isEqualTo("REMOVED");
    }

    @Test
    void 带地线送电_直接闭锁拒绝() {
        jdbc.update("UPDATE device_state SET state = 'ATTACHED' WHERE device_id = 'GW-1101'");
        jdbc.update("UPDATE device_state SET state = 'OFF' WHERE device_id = 'BR-1101'");
        long orderId = draftReviewIssue("gnd-energize", "INT-1101",
                step(1, "BR-1101", "CLOSE_BREAKER", "ON"));

        var response = confirm(orderId, 1, "gnd-energize-c1", "操作人A", "监护人B");
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).contains("R_GROUND_ENERGIZE");

        assertThat(stepStatus(orderId, 1)).isEqualTo("PENDING");
        assertThat(deviceState("BR-1101")).isEqualTo("OFF");
    }

    @Test
    void 母线停电顺序违例_整步拒绝_先断全部出线开关后放行() {
        long orderId = draftReviewIssue("bus-seq", "INT-BUS",
                step(1, "BUS-1", "BUS_OUTAGE", "DEENERGIZED"));

        // 母线下仍有两个出线开关合位：顺序违例，整步拒绝
        var rejected = confirm(orderId, 1, "bus-seq-c1", "操作人A", "监护人B");
        assertThat(rejected.getStatusCode().value()).isEqualTo(422);
        assertThat(rejected.getBody()).contains("R_BUS_SEQUENCE");
        assertThat(stepStatus(orderId, 1)).isEqualTo("PENDING");
        assertThat(deviceState("BUS-1")).isEqualTo("ENERGIZED");
        assertThat(count("interlock_hit",
                "order_id = ? AND rule_code = 'R_BUS_SEQUENCE'", orderId)).isEqualTo(1);

        // 断开全部出线开关后再次确认：放行
        jdbc.update("UPDATE device_state SET state = 'OFF' WHERE device_id IN ('BR-1101','BR-1102')");
        var accepted = confirm(orderId, 1, "bus-seq-c2", "操作人A", "监护人B");
        assertThat(accepted.getStatusCode().value()).isEqualTo(200);
        assertThat(stepStatus(orderId, 1)).isEqualTo("DONE");
        assertThat(deviceState("BUS-1")).isEqualTo("DEENERGIZED");
    }

    @Test
    void 闭锁命中记录可复算() {
        long orderId = draftReviewIssue("hit-trace", "INT-1101",
                step(1, "DIS-1101", "OPEN_DISCONNECTOR", "OPEN"));
        confirm(orderId, 1, "hit-trace-c1", "操作人A", "监护人B");

        var trace = orderService.trace(orderId);
        assertThat(trace.interlockHits()).hasSize(1);
        assertThat(trace.interlockHits().get(0).ruleCode()).isEqualTo("R_LOAD_DISCONNECTOR");
        assertThat(trace.interlockHits().get(0).stepSeq()).isEqualTo(1);
        assertThat(trace.events()).extracting("eventType").contains("INTERLOCK_HIT");
    }
}
