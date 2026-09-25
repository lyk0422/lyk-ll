package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.dto.ActionResult;
import com.example.starter.api.dto.TorqueResult;
import com.example.starter.testsupport.AbstractServiceTest;
import com.example.starter.testsupport.TestRequests;

/**
 * requestId 幂等测试：同键同参返回首次结果；同键异参 409 且不改变工序状态；
 * 失败结果同样幂等；归档终态重复归档幂等成功，迟到测量只记迟到事件。
 */
class IdempotencyAndArchiveServiceTest extends AbstractServiceTest {

    @Autowired
    private OperationService service;

    private String prepareTightenedCard(String cardNo, String station) {
        service.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        service.dispatch(TestRequests.singlePoint("d-" + cardNo, cardNo, station, "N", "P1", "100.000"));
        service.measure(TestRequests.torque("m-" + cardNo, cardNo, "P1", "100", "op1", "T1"));
        return cardNo;
    }

    @Test
    @DisplayName("派工同键同参重放返回首次结果，不重复建卡/不重复占工位")
    void dispatchReplay() {
        var request = TestRequests.singlePoint("req-idem-d", "CARD-E1", "S1", "N", "P1", "100.000");
        Outcome first = service.dispatch(request);
        assertThat(first.status()).isEqualTo(201);
        Outcome replay = service.dispatch(request);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.replay()).isTrue();
        // 工序卡仍只有一张
        Integer cards = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_card WHERE card_no = ?", Integer.class, "CARD-E1");
        assertThat(cards).isEqualTo(1);
    }

    @Test
    @DisplayName("同键异参返回 409，工序状态不变、工位仍被首次卡占用")
    void sameKeyDifferentParamsConflict() {
        service.dispatch(TestRequests.singlePoint("req-conflict", "CARD-E2", "S2", "N", "P1", "100.000"));
        var changed = TestRequests.singlePoint("req-conflict", "CARD-E2-DIFFERENT", "S2", "N", "P1", "100.000");
        Outcome conflict = service.dispatch(changed);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(((java.util.Map<?, ?>) conflict.body()).get("errorCode"))
                .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        // 异参卡未创建
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_card WHERE card_no = ?", Integer.class, "CARD-E2-DIFFERENT"))
                .isZero();
    }

    @Test
    @DisplayName("失败请求同键重放返回首次失败结果（422 幂等），改用新键同参才执行")
    void failureIsIdempotent() {
        prepareTightenedCard("CARD-E3", "S3");
        var samePerson = TestRequests.inspection("req-fail", "CARD-E3", "same", "same");

        Outcome first = service.inspect(samePerson);
        assertThat(first.status()).isEqualTo(422);
        Outcome replay = service.inspect(samePerson);
        assertThat(replay.status()).isEqualTo(422);
        assertThat(replay.replay()).isTrue();
        // 检验记录仍为 0（失败事务已回滚，不改变工序状态）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inspection_record WHERE card_id = "
                        + "(SELECT id FROM operation_card WHERE card_no = 'CARD-E3')", Integer.class))
                .isZero();

        Outcome ok = service.inspect(
                TestRequests.inspection("req-fail-ok", "CARD-E3", "same", "other"));
        assertThat(ok.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("力矩测量重放返回首次结果，attempt 链不重复追加")
    void torqueReplayDoesNotDuplicate() {
        service.registerTool(TestRequests.tool("T1", TestRequests.TODAY));
        service.dispatch(TestRequests.singlePoint("d-e4", "CARD-E4", "S4", "N", "P1", "100.000"));
        var measure = TestRequests.torque("m-e4", "CARD-E4", "P1", "120", "op1", "T1");
        Outcome first = service.measure(measure);
        assertThat(((TorqueResult) first.body()).reworkNo()).isEqualTo(1);
        Outcome replay = service.measure(measure);
        assertThat(replay.replay()).isTrue();
        assertThat(((TorqueResult) replay.body()).attemptNo()).isEqualTo(1);
        assertThat(service.trace("CARD-E4").torqueCurve()).hasSize(1);
        assertThat(service.trace("CARD-E4").reworkLedger()).hasSize(1);
    }

    @Test
    @DisplayName("归档为终态：重复归档（新 requestId）幂等成功，工位守卫已释放")
    void archiveTerminalAndIdempotent() {
        String cardNo = prepareTightenedCard("CARD-E5", "S5");
        service.inspect(TestRequests.inspection("i-e5", cardNo, "a", "b"));

        Outcome first = service.release(TestRequests.release("rel-e5-1", cardNo, "qa"));
        assertThat(first.status()).isEqualTo(200);
        assertThat(service.getCard(cardNo).status()).isEqualTo("ARCHIVED");
        // 工位守卫释放
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM station_guard WHERE station = ?", Integer.class, "S5")).isZero();

        Outcome again = service.release(TestRequests.release("rel-e5-2", cardNo, "qa2"));
        assertThat(again.status()).isEqualTo(200);
        assertThat(((ActionResult) again.body()).status()).isEqualTo("ARCHIVED");
        // 放行记录只有首次一条
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM release_record WHERE card_id = "
                        + "(SELECT id FROM operation_card WHERE card_no = ?)", Integer.class, cardNo))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("归档后迟到测量：只登记迟到事件，不进入力矩 attempt 链，状态保持 ARCHIVED")
    void lateMeasurementAfterArchive() {
        String cardNo = prepareTightenedCard("CARD-E6", "S6");
        service.inspect(TestRequests.inspection("i-e6", cardNo, "a", "b"));
        service.release(TestRequests.release("rel-e6", cardNo, "qa"));

        // 迟到测量：工具校验不再是前置（终态只记录事件），即使工具有效也只记迟到
        Outcome late = service.measure(
                TestRequests.torque("m-late", cardNo, "P1", "200", "op-late", "T1"));
        assertThat(late.status()).isEqualTo(200);
        TorqueResult lateResult = (TorqueResult) late.body();
        assertThat(lateResult.late()).isTrue();
        assertThat(service.getCard(cardNo).status()).isEqualTo("ARCHIVED");
        // 迟到事件 1 条，力矩链仍只有归档前的 1 条
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM late_measurement_event WHERE card_id = "
                        + "(SELECT id FROM operation_card WHERE card_no = ?)", Integer.class, cardNo))
                .isEqualTo(1);
        assertThat(service.trace(cardNo).torqueCurve()).hasSize(1);
    }
}
