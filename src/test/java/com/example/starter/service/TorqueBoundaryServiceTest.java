package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.dto.CardView;
import com.example.starter.api.dto.TorqueResult;
import com.example.starter.testsupport.AbstractServiceTest;
import com.example.starter.testsupport.TestRequests;

/**
 * 力矩 ±5% 边界的端到端 H2 测试：恰在边界合格，超差触发返工。
 */
class TorqueBoundaryServiceTest extends AbstractServiceTest {

    @Autowired
    private OperationService service;

    @Test
    @DisplayName("实测恰为 -5%（95.000）与 +5%（105.000）边界均合格，卡进入 TIGHTENED")
    void exactBoundariesPass() {
        service.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        Outcome dispatch = service.dispatch(
                TestRequests.singlePoint("req-d", "CARD-B1", "S1", "N", "P1", "100.000"));
        assertThat(dispatch.status()).isEqualTo(201);

        // 两个紧固点都需合格才 TIGHTENED —— 单点卡再用一张：先测下边界卡
        Outcome lower = service.measure(
                TestRequests.torque("req-lo", "CARD-B1", "P1", "95.000", "op1", "T1"));
        TorqueResult lowerResult = (TorqueResult) lower.body();
        assertThat(lower.status()).isEqualTo(200);
        assertThat(lowerResult.inTolerance()).isTrue();
        assertThat(lowerResult.reworkNo()).isNull();
        assertThat(lowerResult.cardStatus()).isEqualTo("TIGHTENED");
        assertThat(lowerResult.deviationPct()).isEqualByComparingTo("-5.0000");
    }

    @Test
    @DisplayName("实测 105.000 恰为上边界合格；105.001 超差返工")
    void upperBoundaryPassesJustOutsideFails() {
        service.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        service.dispatch(TestRequests.singlePoint("req-d2", "CARD-B2", "S2", "N", "P1", "100.000"));

        Outcome upper = service.measure(
                TestRequests.torque("req-up", "CARD-B2", "P1", "105.000", "op1", "T1"));
        TorqueResult upperResult = (TorqueResult) upper.body();
        assertThat(upperResult.inTolerance()).isTrue();
        assertThat(upperResult.cardStatus()).isEqualTo("TIGHTENED");
        assertThat(upperResult.deviationPct()).isEqualByComparingTo("5.0000");

        // 另一张卡：越界 0.001 即超差
        service.dispatch(TestRequests.singlePoint("req-d3", "CARD-B3", "S3", "N", "P1", "100.000"));
        Outcome over = service.measure(
                TestRequests.torque("req-over", "CARD-B3", "P1", "105.001", "op1", "T1"));
        TorqueResult overResult = (TorqueResult) over.body();
        assertThat(over.status()).isEqualTo(200);
        assertThat(overResult.inTolerance()).isFalse();
        assertThat(overResult.reworkNo()).isEqualTo(1);
        assertThat(overResult.cardStatus()).isEqualTo("DISPATCHED");
        CardView card = service.getCard("CARD-B3");
        assertThat(card.status()).isEqualTo("DISPATCHED");
    }

    @Test
    @DisplayName("偏差百分比按标称精确计算")
    void deviationRecorded() {
        service.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        service.dispatch(TestRequests.singlePoint("req-d4", "CARD-B4", "S4", "N", "P1", "200.000"));
        Outcome r = service.measure(
                TestRequests.torque("req-dev", "CARD-B4", "P1", "190.000", "op1", "T1"));
        // -5% 边界
        assertThat(((TorqueResult) r.body()).deviationPct()).isEqualByComparingTo(new BigDecimal("-5.0000"));
    }
}
