package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.testsupport.AbstractServiceTest;
import com.example.starter.testsupport.TestRequests;

/**
 * 力矩工具日校验（UTC 日界）与自检互检人员分离测试。
 */
class ToolAndInspectionServiceTest extends AbstractServiceTest {

    @Autowired
    private OperationService service;

    private String prepareTightenedCard(String cardNo, String station) {
        service.dispatch(TestRequests.singlePoint("d-" + cardNo, cardNo, station, "N", "P1", "100.000"));
        return cardNo;
    }

    @Test
    @DisplayName("工具日校验逾期一天：力矩使用直接拒绝 422，不产生力矩记录")
    void expiredToolRejected() {
        prepareTightenedCard("CARD-T1", "S1");
        service.registerTool(TestRequests.tool("T-OLD", LocalDate.parse("2026-09-25")));

        Outcome rejected = service.measure(
                TestRequests.torque("m-expired", "CARD-T1", "P1", "100", "op1", "T-OLD"));
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(((java.util.Map<?, ?>) rejected.body()).get("errorCode"))
                .isEqualTo("TOOL_CALIBRATION_EXPIRED");
        // 被拒测量未追加记录，卡仍在派工态
        assertThat(service.trace("CARD-T1").torqueCurve()).isEmpty();
        assertThat(service.getCard("CARD-T1").status()).isEqualTo("DISPATCHED");
    }

    @Test
    @DisplayName("工具未登记直接拒绝；日校验为未来日期同样拒绝")
    void missingAndFutureToolRejected() {
        prepareTightenedCard("CARD-T2", "S2");

        Outcome missing = service.measure(
                TestRequests.torque("m-missing", "CARD-T2", "P1", "100", "op1", "T-GHOST"));
        assertThat(missing.status()).isEqualTo(422);
        assertThat(((java.util.Map<?, ?>) missing.body()).get("errorCode"))
                .isEqualTo("TOOL_NOT_REGISTERED");

        service.registerTool(TestRequests.tool("T-FUTURE", LocalDate.parse("2026-09-27")));
        Outcome future = service.measure(
                TestRequests.torque("m-future", "CARD-T2", "P1", "100", "op1", "T-FUTURE"));
        assertThat(future.status()).isEqualTo(422);
        assertThat(((java.util.Map<?, ?>) future.body()).get("errorCode"))
                .isEqualTo("TOOL_CALIBRATION_EXPIRED");
    }

    @Test
    @DisplayName("UTC 日界：校验日期当日 23:59:59 仍有效；推进到次日 00:00:00 即逾期")
    void utcDayBoundary() {
        prepareTightenedCard("CARD-T3", "S3");
        service.registerTool(TestRequests.tool("T1", TestRequests.TODAY));

        clock.setInstant(java.time.Instant.parse("2026-09-26T23:59:59Z"));
        Outcome stillValid = service.measure(
                TestRequests.torque("m-edge1", "CARD-T3", "P1", "100", "op1", "T1"));
        assertThat(stillValid.status()).isEqualTo(200);

        // 新卡测日界跨越
        service.dispatch(TestRequests.singlePoint("d-t4", "CARD-T4", "S4", "N", "P1", "100.000"));
        clock.setInstant(java.time.Instant.parse("2026-09-27T00:00:00Z"));
        Outcome expired = service.measure(
                TestRequests.torque("m-edge2", "CARD-T4", "P1", "100", "op1", "T1"));
        assertThat(expired.status()).isEqualTo(422);
        assertThat(((java.util.Map<?, ?>) expired.body()).get("errorCode"))
                .isEqualTo("TOOL_CALIBRATION_EXPIRED");
    }

    @Test
    @DisplayName("自检与互检同一人双签拒绝 422，不产生检验记录")
    void selfMutualSamePersonRejected() {
        service.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        prepareTightenedCard("CARD-I1", "S5");
        service.measure(TestRequests.torque("m-i1", "CARD-I1", "P1", "100", "op1", "T1"));

        Outcome rejected = service.inspect(
                TestRequests.inspection("i-same", "CARD-I1", "person-X", "person-X"));
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(((java.util.Map<?, ?>) rejected.body()).get("errorCode"))
                .isEqualTo("SELF_MUTUAL_SAME_PERSON");
        assertThat(service.getCard("CARD-I1").status()).isEqualTo("TIGHTENED");

        // 换不同人员后通过
        Outcome ok = service.inspect(
                TestRequests.inspection("i-ok", "CARD-I1", "person-X", "person-Y"));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(service.getCard("CARD-I1").status()).isEqualTo("INSPECTED");
    }

    @Test
    @DisplayName("力矩紧固未完成不能自检互检")
    void inspectionBeforeTighteningRejected() {
        prepareTightenedCard("CARD-I2", "S6");
        Outcome rejected = service.inspect(
                TestRequests.inspection("i-early", "CARD-I2", "a", "b"));
        assertThat(rejected.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("工具次日重新完成日校验（upsert）后恢复可用")
    void toolRecalibratedNextDay() {
        service.registerTool(TestRequests.tool("T-RECAL", TestRequests.TODAY));
        service.dispatch(TestRequests.singlePoint("d-r1", "CARD-RC1", "S7", "N", "P1", "100.000"));
        service.dispatch(TestRequests.singlePoint("d-r2", "CARD-RC2", "S8", "N", "P1", "100.000"));

        clock.setInstant(java.time.Instant.parse("2026-09-27T08:00:00Z"));
        // 逾期
        Outcome expired = service.measure(
                TestRequests.torque("m-oldcal", "CARD-RC1", "P1", "100", "op1", "T-RECAL"));
        assertThat(expired.status()).isEqualTo(422);

        // 重新日校验（同编号更新日期）
        service.registerTool(TestRequests.tool("T-RECAL", LocalDate.parse("2026-09-27")));
        Outcome ok = service.measure(
                TestRequests.torque("m-newcal", "CARD-RC2", "P1", "100", "op1", "T-RECAL"));
        assertThat(ok.status()).isEqualTo(200);
    }
}
