package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.dto.TorqueResult;
import com.example.starter.api.dto.TraceView;
import com.example.starter.testsupport.AbstractServiceTest;
import com.example.starter.testsupport.TestRequests;

/**
 * 返工台账测试：超差即返工；同一紧固点恰返工 2 次合法，第 3 次仍超差整批隔离评审；
 * 力矩记录链追加、原始记录不覆盖，台账可复算。
 */
class ReworkQuarantineServiceTest extends AbstractServiceTest {

    @Autowired
    private OperationService service;

    @Test
    @DisplayName("超差→返工1→仍超差→返工2（合法）→第三次仍超差→整批隔离 422")
    void twoReworksAllowedThenQuarantine() {
        service.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        service.dispatch(TestRequests.singlePoint("req-d", "CARD-R1", "S1", "N", "P1", "100.000"));

        Outcome first = service.measure(
                TestRequests.torque("req-m1", "CARD-R1", "P1", "120.000", "op1", "T1"));
        TorqueResult r1 = (TorqueResult) first.body();
        assertThat(r1.inTolerance()).isFalse();
        assertThat(r1.attemptNo()).isEqualTo(1);
        assertThat(r1.reworkNo()).isEqualTo(1);
        assertThat(r1.cardStatus()).isEqualTo("DISPATCHED");

        Outcome second = service.measure(
                TestRequests.torque("req-m2", "CARD-R1", "P1", "121.000", "op1", "T1"));
        TorqueResult r2 = (TorqueResult) second.body();
        assertThat(r2.attemptNo()).isEqualTo(2);
        assertThat(r2.reworkNo()).isEqualTo(2); // 恰 2 次返工合法
        assertThat(r2.cardStatus()).isEqualTo("DISPATCHED");

        // 第三次测量仍超差：整批隔离评审，HTTP 422
        Outcome third = service.measure(
                TestRequests.torque("req-m3", "CARD-R1", "P1", "122.000", "op1", "T1"));
        assertThat(third.status()).isEqualTo(422);
        TorqueResult r3 = (TorqueResult) third.body();
        assertThat(r3.attemptNo()).isEqualTo(3);
        assertThat(r3.reworkNo()).isNull();
        assertThat(r3.cardStatus()).isEqualTo("QUARANTINED");

        assertThat(service.getCard("CARD-R1").status()).isEqualTo("QUARANTINED");
    }

    @Test
    @DisplayName("返工2次后第三次测量合格则正常通过，隔离只在仍超差时触发")
    void recoverAfterTwoReworks() {
        service.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        service.dispatch(TestRequests.singlePoint("req-d", "CARD-R2", "S2", "N", "P1", "100.000"));

        service.measure(TestRequests.torque("req-m1", "CARD-R2", "P1", "80", "op1", "T1"));
        service.measure(TestRequests.torque("req-m2", "CARD-R2", "P1", "81", "op1", "T1"));
        Outcome third = service.measure(
                TestRequests.torque("req-m3", "CARD-R2", "P1", "100.000", "op1", "T1"));
        TorqueResult r3 = (TorqueResult) third.body();
        assertThat(r3.inTolerance()).isTrue();
        assertThat(r3.attemptNo()).isEqualTo(3);
        assertThat(r3.reworkNo()).isNull();
        assertThat(r3.cardStatus()).isEqualTo("TIGHTENED");
    }

    @Test
    @DisplayName("返工恰1次后复测合格：力矩链保留超差原始记录，返工台账可复算")
    void appendOnlyChainAndReworkLedger() {
        service.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        service.dispatch(TestRequests.singlePoint("req-d", "CARD-R3", "S3", "N", "P1", "100.000"));

        service.measure(TestRequests.torque("req-m1", "CARD-R3", "P1", "90.000", "op1", "T1"));
        service.measure(TestRequests.torque("req-m2", "CARD-R3", "P1", "100.000", "op1", "T1"));

        TraceView trace = service.trace("CARD-R3");
        assertThat(trace.cardStatus()).isEqualTo("TIGHTENED");
        // 力矩曲线 2 条：原始超差记录未被覆盖
        assertThat(trace.torqueCurve()).hasSize(2);
        assertThat(trace.torqueCurve().get(0).attemptNo()).isEqualTo(1);
        assertThat(trace.torqueCurve().get(0).inTolerance()).isFalse();
        assertThat(trace.torqueCurve().get(0).measuredTorque()).isEqualByComparingTo("90.000");
        assertThat(trace.torqueCurve().get(1).attemptNo()).isEqualTo(2);
        assertThat(trace.torqueCurve().get(1).inTolerance()).isTrue();
        // 返工台账 1 条，且记录触发返工的 requestId
        assertThat(trace.reworkLedger()).hasSize(1);
        assertThat(trace.reworkLedger().get(0).reworkNo()).isEqualTo(1);
        assertThat(trace.reworkLedger().get(0).requestId()).isEqualTo("req-m1");
    }

    @Test
    @DisplayName("隔离后拒绝新测量与放行")
    void quarantinedFrozen() {
        service.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        service.dispatch(TestRequests.singlePoint("req-d", "CARD-R4", "S4", "N", "P1", "100.000"));
        service.measure(TestRequests.torque("req-m1", "CARD-R4", "P1", "80", "op1", "T1"));
        service.measure(TestRequests.torque("req-m2", "CARD-R4", "P1", "80", "op1", "T1"));
        service.measure(TestRequests.torque("req-m3", "CARD-R4", "P1", "80", "op1", "T1"));

        Outcome again = service.measure(
                TestRequests.torque("req-m4", "CARD-R4", "P1", "100", "op1", "T1"));
        assertThat(again.status()).isEqualTo(422);
        assertThat(((java.util.Map<?, ?>) again.body()).get("errorCode")).isEqualTo("CARD_QUARANTINED");

        Outcome release = service.release(TestRequests.release("req-rel", "CARD-R4", "qa"));
        assertThat(release.status()).isEqualTo(422);
    }
}
