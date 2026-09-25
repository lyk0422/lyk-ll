package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.dto.WitnessResult;
import com.example.starter.testsupport.AbstractServiceTest;
import com.example.starter.testsupport.TestClockConfig;
import com.example.starter.testsupport.TestRequests;

/**
 * H 点见证流程测试：缺席转等待、等待满 2 小时（含恰满边界）升级、
 * 未见证不得进入覆盖工序、见证到场后放行。
 */
class WitnessFlowServiceTest extends AbstractServiceTest {

    @Autowired
    private OperationService service;

    /** 派工 H 点卡 → 力矩合格 → 自检互检完成，返回卡号。 */
    private String prepareInspectedHCard(String cardNo, String station) {
        service.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        service.dispatch(TestRequests.singlePoint("d-" + cardNo, cardNo, station, "H", "P1", "100.000"));
        service.measure(TestRequests.torque("m-" + cardNo, cardNo, "P1", "100.000", "op1", "T1"));
        service.inspect(TestRequests.inspection("i-" + cardNo, cardNo, "self-A", "mutual-B"));
        return cardNo;
    }

    @Test
    @DisplayName("见证缺席转等待，升级时刻为缺席起恰满 2 小时")
    void absentTransitionsToWaiting() {
        prepareInspectedHCard("CARD-W1", "S1");

        Outcome absent = service.witness(TestRequests.witnessAbsent("w-absent", "CARD-W1"));
        assertThat(absent.status()).isEqualTo(200);
        WitnessResult result = (WitnessResult) absent.body();
        assertThat(result.witnessStatus()).isEqualTo("WAITING");
        assertThat(result.cardStatus()).isEqualTo("WAITING_WITNESS");
        assertThat(result.escalated()).isFalse();
        assertThat(result.escalateAt()).isEqualTo(TestClockConfig.FIXED_NOW.plus(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("等待未满 2 小时不升级；恰满 2 小时（边界）升级，覆盖工序仍被拒绝")
    void exactlyTwoHoursTriggersEscalation() {
        prepareInspectedHCard("CARD-W2", "S2");
        service.witness(TestRequests.witnessAbsent("w-absent", "CARD-W2"));

        // 未见证不得进入覆盖工序
        Outcome blocked = service.coverage(TestRequests.coverage("cov-blocked", "CARD-W2"));
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(((java.util.Map<?, ?>) blocked.body()).get("errorCode")).isEqualTo("WITNESS_REQUIRED");

        // 差 1 秒未满：不升级
        clock.advance(Duration.ofHours(2).minusSeconds(1));
        Outcome notYet = service.checkEscalation("CARD-W2");
        WitnessResult notYetResult = (WitnessResult) notYet.body();
        assertThat(notYetResult.escalated()).isFalse();
        assertThat(service.getCard("CARD-W2").status()).isEqualTo("WAITING_WITNESS");

        // 恰满 2 小时：升级
        clock.advance(Duration.ofSeconds(1));
        Outcome escalated = service.checkEscalation("CARD-W2");
        WitnessResult escalatedResult = (WitnessResult) escalated.body();
        assertThat(escalatedResult.escalated()).isTrue();
        assertThat(escalatedResult.witnessStatus()).isEqualTo("ESCALATED");
        assertThat(service.getCard("CARD-W2").status()).isEqualTo("ESCALATED");

        // 升级后仍未见证，覆盖继续被拒绝
        Outcome stillBlocked = service.coverage(TestRequests.coverage("cov-blocked-2", "CARD-W2"));
        assertThat(stillBlocked.status()).isEqualTo(422);

        // 升级检查幂等：再次检查保持 ESCALATED，不重复追加链
        Outcome recheck = service.checkEscalation("CARD-W2");
        assertThat(((WitnessResult) recheck.body()).escalated()).isTrue();
        assertThat(service.trace("CARD-W2").witnessChain())
                .extracting(com.example.starter.api.dto.TraceView.WitnessPoint::status)
                .filteredOn("ESCALATED"::equals)
                .hasSize(1);
    }

    @Test
    @DisplayName("等待期间见证人到场：转 WITNESSED，允许覆盖工序并放行归档")
    void witnessArrivesDuringWaiting() {
        prepareInspectedHCard("CARD-W3", "S3");
        service.witness(TestRequests.witnessAbsent("w-absent", "CARD-W3"));
        clock.advance(Duration.ofMinutes(30));

        Outcome present = service.witness(TestRequests.witnessPresent("w-present", "CARD-W3", "witness-Q"));
        WitnessResult presentResult = (WitnessResult) present.body();
        assertThat(presentResult.witnessStatus()).isEqualTo("PRESENT");
        assertThat(service.getCard("CARD-W3").status()).isEqualTo("WITNESSED");

        Outcome coverage = service.coverage(TestRequests.coverage("cov-ok", "CARD-W3"));
        assertThat(coverage.status()).isEqualTo(200);

        Outcome release = service.release(TestRequests.release("rel-ok", "CARD-W3", "qa-lead"));
        assertThat(release.status()).isEqualTo(200);
        assertThat(service.getCard("CARD-W3").status()).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("H 点未见证直接放行被拒绝；到场缺见证人姓名被 400 拒绝")
    void releaseWithoutWitnessRejected() {
        prepareInspectedHCard("CARD-W4", "S4");

        Outcome releaseBlocked = service.release(TestRequests.release("rel-blocked", "CARD-W4", "qa"));
        assertThat(releaseBlocked.status()).isEqualTo(422);
        assertThat(((java.util.Map<?, ?>) releaseBlocked.body()).get("errorCode"))
                .isEqualTo("WITNESS_REQUIRED");

        Outcome noName = service.witness(
                new com.example.starter.api.dto.WitnessRequest("w-noname", "CARD-W4", "PRESENT", "  "));
        assertThat(noName.status()).isEqualTo(400);
        assertThat(((java.util.Map<?, ?>) noName.body()).get("errorCode"))
                .isEqualTo("WITNESS_NAME_REQUIRED");
    }

    @Test
    @DisplayName("非 H 点工序无需见证：自检互检后即可覆盖与放行")
    void nonHPointSkipsWitness() {
        service.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        service.dispatch(TestRequests.singlePoint("d-w5", "CARD-W5", "S5", "N", "P1", "100.000"));
        service.measure(TestRequests.torque("m-w5", "CARD-W5", "P1", "100", "op1", "T1"));
        service.inspect(TestRequests.inspection("i-w5", "CARD-W5", "a", "b"));

        assertThat(service.coverage(TestRequests.coverage("cov-w5", "CARD-W5")).status()).isEqualTo(200);
        assertThat(service.release(TestRequests.release("rel-w5", "CARD-W5", "qa")).status()).isEqualTo(200);
    }
}
