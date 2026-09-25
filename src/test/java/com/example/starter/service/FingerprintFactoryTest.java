package com.example.starter.service;

import com.example.starter.domain.ValveAction;
import com.example.starter.dto.AdjustRequest;
import com.example.starter.dto.AdjustStep;
import com.example.starter.dto.ArchiveRequest;
import com.example.starter.dto.EvaluateRequest;
import com.example.starter.testsupport.Requests;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等指纹纯单元测试：同参稳定、任一业务字段变化即异指纹；列表顺序不影响同参判定。
 */
class FingerprintFactoryTest {

    private final FingerprintFactory factory = new FingerprintFactory();

    @Test
    void sameBusinessFieldsProduceSameFingerprint_evenInDifferentOrder() {
        AdjustRequest a = Requests.adjust("req-1", "ST",
                Requests.step("U1", ValveAction.OPEN, "5.000"),
                Requests.step("U2", ValveAction.CLOSE, "10.000"));
        AdjustRequest b = Requests.adjust("req-1", "ST",
                Requests.step("U2", ValveAction.CLOSE, "10"),
                Requests.step("U1", ValveAction.OPEN, "5.0"));
        assertThat(factory.adjust(a)).isEqualTo(factory.adjust(b));
    }

    @Test
    void anyBusinessFieldChangeProducesDifferentFingerprint() {
        AdjustRequest base = Requests.adjust("req-1", "ST",
                Requests.step("U1", ValveAction.OPEN, "5"));
        AdjustRequest differentDelta = Requests.adjust("req-1", "ST",
                Requests.step("U1", ValveAction.OPEN, "6"));
        AdjustRequest differentAction = Requests.adjust("req-1", "ST",
                Requests.step("U1", ValveAction.CLOSE, "5"));
        AdjustRequest differentStation = Requests.adjust("req-1", "ST2",
                Requests.step("U1", ValveAction.OPEN, "5"));
        AdjustRequest differentRequestId = Requests.adjust("req-2", "ST",
                Requests.step("U1", ValveAction.OPEN, "5"));

        String fp = factory.adjust(base);
        assertThat(List.of(
                factory.adjust(differentDelta),
                factory.adjust(differentAction),
                factory.adjust(differentStation),
                factory.adjust(differentRequestId))).doesNotContain(fp);
    }

    @Test
    void evaluateAndArchiveFingerprintsCoverAllFields() {
        assertThat(factory.evaluate(Requests.evaluate("r", "ST", "JUDGMENT")))
                .isNotEqualTo(factory.evaluate(Requests.evaluate("r", "ST", "RE_EVAL")));
        assertThat(factory.archive(new ArchiveRequest("r", "ST")))
                .isNotEqualTo(factory.archive(new ArchiveRequest("r", "ST2")));
    }
}
