package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.OrderDetailResponse;
import com.example.starter.dto.OrderReceiveRequest;
import com.example.starter.dto.OrderReceiveResponse;
import com.example.starter.dto.ReportRequest;
import com.example.starter.dto.ReportResponse;
import com.example.starter.dto.SettleResponse;
import com.example.starter.web.ApiBadRequestException;
import com.example.starter.web.ApiConflictException;

/**
 * H2 事务集成测试：主流程、恰等不降机、15% 边界、尾差、乱序归位、
 * 10 分钟恰超时告警重分摊、封存终态与迟到回报。
 */
@SpringBootTest
class DispatchFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DispatchService dispatchService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    @Test
    @DisplayName("接收即分摊：恰等全场可用出力时不降机")
    void receiveExactTargetNoCurtailment() {
        OrderReceiveResponse resp = dispatchService.receive(
                new OrderReceiveRequest("req-1", FARM, 6000));

        assertThat(resp.noCurtailment()).isTrue();
        assertThat(resp.curtailedKw()).isZero();
        assertThat(resp.allocations()).allSatisfy(a -> assertThat(a.curtailKw()).isZero());
        assertThat(resp.allocations()).extracting(a -> a.targetPowerKw())
                .containsExactly(2000, 1600, 2400);
        assertThat(resp.status()).isEqualTo("DISPATCHED");
        assertThat(resp.replayed()).isFalse();
    }

    @Test
    @DisplayName("分摊合计恰等于目标，单机目标不低于技术下限，尾差归入最大可降机组")
    void allocationSumsToTargetWith15PercentFloor() {
        // 可降空间 1700/1300/1950；压减 1000 → 目标 5000。
        OrderReceiveResponse resp = dispatchService.receive(
                new OrderReceiveRequest("req-2", FARM, 5000));

        assertThat(resp.allocations()).extracting(a -> a.targetPowerKw())
                .hasSize(3);
        int sum = resp.allocations().stream().mapToInt(a -> a.targetPowerKw()).sum();
        assertThat(sum).isEqualTo(5000);
        assertThat(resp.allocations()).allSatisfy(a ->
                assertThat(a.targetPowerKw()).isGreaterThanOrEqualTo(a.technicalMinKw()));
        // 尾差 1 归可降空间最大的 T-03。
        // 精确比例：1000*1700/4950=343, 1000*1300/4950=262, 1000*1950/4950=393；和 998，尾差 2 归 T-03。
        var byCode = resp.allocations().stream()
                .collect(java.util.stream.Collectors.toMap(a -> a.turbineCode(), a -> a));
        assertThat(byCode.get("T-01").curtailKw()).isEqualTo(343);
        assertThat(byCode.get("T-02").curtailKw()).isEqualTo(262);
        assertThat(byCode.get("T-03").curtailKw()).isEqualTo(395);
    }

    @Test
    @DisplayName("目标恰为全场下限之和：三机组恰降至 15%，合法")
    void targetExactlyAtFloor() {
        OrderReceiveResponse resp = dispatchService.receive(
                new OrderReceiveRequest("req-3", FARM, 1050));

        assertThat(resp.allocations()).extracting(a -> a.targetPowerKw())
                .containsExactly(300, 300, 450);
    }

    @Test
    @DisplayName("同键同参重放返回首次结果；同键异参返回 409")
    void idempotentReplayAndConflict() {
        OrderReceiveRequest first = new OrderReceiveRequest("req-4", FARM, 5000);
        OrderReceiveResponse r1 = dispatchService.receive(first);
        OrderReceiveResponse r2 = dispatchService.receive(first);

        assertThat(r2.replayed()).isTrue();
        assertThat(r2.orderId()).isEqualTo(r1.orderId());
        assertThat(r2.allocations()).isEqualTo(r1.allocations());

        OrderReceiveRequest changed = new OrderReceiveRequest("req-4", FARM, 4999);
        assertThatThrownBy(() -> dispatchService.receive(changed))
                .isInstanceOf(ApiConflictException.class);
    }

    @Test
    @DisplayName("乱序回报按时刻归位，且按机组＋时刻去重")
    void outOfOrderReportsAreSortedAndDeduplicated() {
        OrderReceiveResponse order = dispatchService.receive(
                new OrderReceiveRequest("req-5", FARM, 5000));

        // 先报较晚时刻，再报较早时刻（乱序）。
        dispatchService.report(new ReportRequest("rp-2", "T-01",
                localInstant(10, 5, 0), 1700));
        dispatchService.report(new ReportRequest("rp-1", "T-01",
                localInstant(10, 2, 0), 1800));
        // 同机组＋同时刻、不同 requestId 再报：去重，返回首次结果。
        ReportResponse dup = dispatchService.report(new ReportRequest("rp-3", "T-01",
                localInstant(10, 2, 0), 1800));
        assertThat(dup.replayed()).isTrue();
        assertThat(dup.powerKw()).isEqualTo(1800);

        OrderDetailResponse detail = dispatchService.detail(order.orderNo());
        assertThat(detail.executionCurve()).hasSize(2);
        // 按时刻归位：10:02 在 10:05 前。
        assertThat(detail.executionCurve().get(0).measuredAt()).isEqualTo(localInstant(10, 2, 0));
        assertThat(detail.executionCurve().get(1).measuredAt()).isEqualTo(localInstant(10, 5, 0));
    }

    @Test
    @DisplayName("下发未满 10 分钟不告警；恰满 10 分钟未达标触发告警并重分摊")
    void alertFiresExactlyAtTenMinutesAndReallocates() {
        OrderReceiveResponse order = dispatchService.receive(
                new OrderReceiveRequest("req-6", FARM, 5000));
        String orderNo = order.orderNo();

        // 10:09:59（早于 dueAt 10:10:00）全场仍高于目标，不告警。
        clock.pinAtLocal(java.time.LocalDateTime.of(2026, 9, 26, 10, 9, 59));
        dispatchService.report(new ReportRequest("rp-a", "T-01",
                localInstant(10, 9, 0), 1900));
        assertThat(dispatchService.detail(orderNo).alerts()).isEmpty();
        assertThat(dispatchService.detail(orderNo).currentCycleNo()).isEqualTo(1);

        // 恰满 10 分钟 10:10:00：全场出力仍超目标 → 告警 + 重分摊（第 2 轮）。
        clock.pinAtLocal(java.time.LocalDateTime.of(2026, 9, 26, 10, 10, 0));
        dispatchService.report(new ReportRequest("rp-b", "T-01",
                localInstant(10, 10, 0), 1900));

        OrderDetailResponse after = dispatchService.detail(orderNo);
        assertThat(after.alerts()).hasSize(1);
        assertThat(after.alerts().get(0).cycleNo()).isEqualTo(1);
        assertThat(after.alerts().get(0).reallocatedCycleNo()).isEqualTo(2);
        assertThat(after.currentCycleNo()).isEqualTo(2);
        assertThat(after.cycles()).hasSize(2);
        // 重分摊以最新可用出力为基准（T-01 已更新为 1900），新轮目标合计仍恰为 5000。
        int newCycleSum = after.cycles().get(1).allocations().stream()
                .mapToInt(a -> a.targetPowerKw()).sum();
        assertThat(newCycleSum).isEqualTo(5000);
        // 同一轮不重复告警。
        dispatchService.report(new ReportRequest("rp-c", "T-02",
                localInstant(10, 10, 30), 1500));
        assertThat(dispatchService.detail(orderNo).alerts()).hasSize(1);
    }

    @Test
    @DisplayName("恰满 10 分钟但全场已达标：不告警")
    void noAlertWhenTargetReachedAtTenMinutes() {
        OrderReceiveResponse order = dispatchService.receive(
                new OrderReceiveRequest("req-7", FARM, 5000));

        // 判定时刻前（10:09:5x）三机组已降到目标水平：1900+1200+1900=5000，此时不判超时。
        clock.pinAtLocal(java.time.LocalDateTime.of(2026, 9, 26, 10, 9, 50));
        dispatchService.report(new ReportRequest("rp-1", "T-01", localInstant(10, 9, 50), 1900));
        clock.pinAtLocal(java.time.LocalDateTime.of(2026, 9, 26, 10, 9, 51));
        dispatchService.report(new ReportRequest("rp-2", "T-02", localInstant(10, 9, 51), 1200));
        clock.pinAtLocal(java.time.LocalDateTime.of(2026, 9, 26, 10, 9, 52));
        dispatchService.report(new ReportRequest("rp-3", "T-03", localInstant(10, 9, 52), 1900));

        // 恰满 10 分钟的回报确认：全场 4950 ≤ 目标 5000，不告警。
        clock.pinAtLocal(java.time.LocalDateTime.of(2026, 9, 26, 10, 10, 0));
        dispatchService.report(new ReportRequest("rp-4", "T-01", localInstant(10, 10, 0), 1850));

        OrderDetailResponse detail = dispatchService.detail(order.orderNo());
        assertThat(detail.alerts()).isEmpty();
        assertThat(detail.currentCycleNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("回报同键同参重放幂等；同键异参 409")
    void reportIdempotentReplayAndConflict() {
        dispatchService.receive(new OrderReceiveRequest("req-rp", FARM, 5000));
        ReportRequest req = new ReportRequest(
                "rp-key", "T-01", localInstant(10, 2, 0), 1800);

        ReportResponse first = dispatchService.report(req);
        assertThat(first.replayed()).isFalse();
        ReportResponse replay = dispatchService.report(req);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.powerKw()).isEqualTo(1800);

        ReportRequest changed = new ReportRequest(
                "rp-key", "T-01", localInstant(10, 2, 0), 1700);
        assertThatThrownBy(() -> dispatchService.report(changed))
                .isInstanceOf(ApiConflictException.class);

        // 幂等重放不产生重复回报点。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_report", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("目标高于全场可用出力：400（无需限电）")
    void targetAboveAvailableRejected() {
        assertThatThrownBy(() ->
                dispatchService.receive(new OrderReceiveRequest("req-bad", FARM, 6001)))
                .isInstanceOf(ApiBadRequestException.class);
    }

    @Test
    @DisplayName("对已被作废的指令日结封存：400")
    void settleSupersededOrderRejected() {
        OrderReceiveResponse old = dispatchService.receive(
                new OrderReceiveRequest("req-old", FARM, 5000));
        pinClock(10, 5, 0);
        dispatchService.receive(new OrderReceiveRequest("req-new", FARM, 4500));

        assertThatThrownBy(() -> dispatchService.settle(old.orderNo()))
                .isInstanceOf(ApiBadRequestException.class);
    }

    @Test
    @DisplayName("日结封存为终态：重复封存幂等成功，封存后迟到回报只记迟到事件")
    void settleIsTerminalAndLateReportsRecorded() {
        OrderReceiveResponse order = dispatchService.receive(
                new OrderReceiveRequest("req-8", FARM, 5000));

        SettleResponse s1 = dispatchService.settle(order.orderNo());
        assertThat(s1.status()).isEqualTo("SETTLED");
        assertThat(s1.replayed()).isFalse();
        SettleResponse s2 = dispatchService.settle(order.orderNo());
        assertThat(s2.replayed()).isTrue();
        assertThat(s2.settledAt()).isEqualTo(s1.settledAt());

        // 封存后回报：late=true，不进执行曲线。
        ReportResponse late = dispatchService.report(new ReportRequest("rp-late", "T-01",
                localInstant(10, 20, 0), 1800));
        assertThat(late.late()).isTrue();

        OrderDetailResponse detail = dispatchService.detail(order.orderNo());
        assertThat(detail.executionCurve()).isEmpty();
        assertThat(detail.lateReports()).hasSize(1);
        assertThat(detail.lateReports().get(0).turbineCode()).isEqualTo("T-01");

        // 迟到回报同键重放仍幂等。
        ReportRequest lateReq = new ReportRequest("rp-late", "T-01",
                localInstant(10, 20, 0), 1800);
        ReportResponse lateReplay = dispatchService.report(lateReq);
        assertThat(lateReplay.replayed()).isTrue();
        assertThat(dispatchService.detail(order.orderNo()).lateReports()).hasSize(1);
    }
}
