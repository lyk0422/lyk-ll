package com.example.starter.portability;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.example.starter.portability.dto.ApplyRequest;
import com.example.starter.portability.dto.EventResponse;
import com.example.starter.portability.dto.RequestIdRequest;
import com.example.starter.portability.dto.StoredResponse;
import com.example.starter.portability.support.AbstractPortabilityTest;
import com.example.starter.portability.support.TestClockConfig;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 携号转网主流程与业务边界测试：资格、授权码有效期、生效窗口、幂等与撤销。
 */
class PortabilityServiceTest extends AbstractPortabilityTest {

    private static final Instant WINDOW_TIME = Instant.parse("2026-09-25T02:30:00Z");

    @Autowired
    private PortabilityService service;

    @Test
    @DisplayName("主流程：申请-签码-确认-窗口内广播生效，事件按序可查，生效后授权码作废")
    void fullFlowActivatesInWindow() {
        insertProfile("13900000001", false, false, null);
        long flowId = applyOk("13900000001", "req-apply-1");
        issueOk(flowId, "req-code-1");
        confirmOk(flowId, "req-confirm-1");

        clock.setInstant(WINDOW_TIME);
        StoredResponse broadcast = service.broadcast(flowId, new RequestIdRequest("req-broadcast-1"));
        assertThat(broadcast.httpStatus()).isEqualTo(200);
        JsonNode body = parseJson(broadcast.body());
        assertThat(body.get("status").asText()).isEqualTo("ACTIVATED");

        List<EventResponse> events = service.getEvents(flowId);
        assertThat(events).extracting(EventResponse::eventType)
                .containsExactly("APPLIED", "AUTH_CODE_ISSUED", "DONOR_CONFIRMED", "ACTIVATED");

        // 生效后授权码作废：迟到的携出方确认不得改变结果
        StoredResponse lateConfirm = service.donorConfirm(flowId, new RequestIdRequest("req-confirm-late"));
        assertThat(lateConfirm.httpStatus()).isEqualTo(409);
        assertThat(parseJson(lateConfirm.body()).get("code").asText()).isEqualTo("FLOW_ALREADY_ACTIVATED");
        assertThat(flowStatus(flowId)).isEqualTo("ACTIVATED");
    }

    @Test
    @DisplayName("资格边界：距上次携转恰满 120 日通过，差 1 毫秒则拒绝")
    void eligibilityBoundaryAt120Days() {
        Instant now = TestClockConfig.BASE_INSTANT;
        insertProfile("13900000011", false, false, now.minus(Duration.ofDays(120)));
        StoredResponse eligible = service.apply(new ApplyRequest("req-e1", "13900000011"));
        assertThat(eligible.httpStatus()).isEqualTo(200);

        insertProfile("13900000012", false, false,
                now.minus(Duration.ofDays(120)).plusMillis(1));
        StoredResponse rejected = service.apply(new ApplyRequest("req-e2", "13900000012"));
        assertThat(rejected.httpStatus()).isEqualTo(422);
        assertThat(parseJson(rejected.body()).get("code").asText()).isEqualTo("ELIGIBILITY_FAILED");
        assertThat(parseJson(rejected.body()).get("message").asText()).contains("120");
    }

    @Test
    @DisplayName("资格拒绝：欠费、在网约定期限、档案不存在均返回 422")
    void eligibilityRejections() {
        insertProfile("13900000021", true, false, null);
        insertProfile("13900000022", false, true, null);

        StoredResponse arrears = service.apply(new ApplyRequest("req-a1", "13900000021"));
        assertThat(arrears.httpStatus()).isEqualTo(422);
        assertThat(parseJson(arrears.body()).get("message").asText()).contains("欠费");

        StoredResponse contract = service.apply(new ApplyRequest("req-a2", "13900000022"));
        assertThat(contract.httpStatus()).isEqualTo(422);
        assertThat(parseJson(contract.body()).get("message").asText()).contains("在网约定");

        StoredResponse missing = service.apply(new ApplyRequest("req-a3", "13900000023"));
        assertThat(missing.httpStatus()).isEqualTo(422);
        assertThat(parseJson(missing.body()).get("message").asText()).contains("档案不存在");
    }

    @Test
    @DisplayName("授权码恰满 24 小时即失效；差 1 毫秒仍可确认")
    void authCodeExpiresExactlyAt24Hours() {
        insertProfile("13900000031", false, false, null);
        long flowId = applyOk("13900000031", "req-apply-31");
        Instant issuedAt = TestClockConfig.BASE_INSTANT;
        issueOk(flowId, "req-code-31");

        // 恰满 24 小时：失效
        clock.setInstant(issuedAt.plus(Duration.ofHours(24)));
        StoredResponse expired = service.donorConfirm(flowId, new RequestIdRequest("req-confirm-31"));
        assertThat(expired.httpStatus()).isEqualTo(422);
        assertThat(parseJson(expired.body()).get("code").asText()).isEqualTo("AUTH_CODE_EXPIRED");
        assertThat(flowStatus(flowId)).isEqualTo("CODE_ISSUED");

        // 差 1 毫秒未满 24 小时：可确认
        insertProfile("13900000032", false, false, null);
        clock.setInstant(issuedAt);
        long flowId2 = applyOk("13900000032", "req-apply-32");
        issueOk(flowId2, "req-code-32");
        clock.setInstant(issuedAt.plus(Duration.ofHours(24)).minusMillis(1));
        confirmOk(flowId2, "req-confirm-32");
    }

    @Test
    @DisplayName("生效窗口：02:00 起可生效，04:00 整点视为窗口外并保留待生效状态")
    void broadcastWindowBoundaries() {
        // 恰在 04:00 整点：窗口外，保留待生效状态
        insertProfile("13900000041", false, false, null);
        long flowId = applyOk("13900000041", "req-apply-41");
        issueOk(flowId, "req-code-41");
        confirmOk(flowId, "req-confirm-41");
        clock.setInstant(Instant.parse("2026-09-25T04:00:00Z"));
        StoredResponse deferred = service.broadcast(flowId, new RequestIdRequest("req-bc-41"));
        assertThat(deferred.httpStatus()).isEqualTo(422);
        assertThat(parseJson(deferred.body()).get("code").asText()).isEqualTo("OUT_OF_WINDOW");
        assertThat(flowStatus(flowId)).isEqualTo("DONOR_CONFIRMED");
        assertThat(countEvents(flowId, "BROADCAST_DEFERRED")).isEqualTo(1);

        // 窗口内 03:59:59.999：可生效
        clock.setInstant(Instant.parse("2026-09-25T03:59:59.999Z"));
        StoredResponse ok = service.broadcast(flowId, new RequestIdRequest("req-bc-41b"));
        assertThat(ok.httpStatus()).isEqualTo(200);
        assertThat(flowStatus(flowId)).isEqualTo("ACTIVATED");

        // 01:59:59 窗口外，02:00 整点窗口内
        insertProfile("13900000042", false, false, null);
        long flowId2 = applyOk("13900000042", "req-apply-42");
        issueOk(flowId2, "req-code-42");
        confirmOk(flowId2, "req-confirm-42");
        clock.setInstant(Instant.parse("2026-09-25T01:59:59Z"));
        assertThat(service.broadcast(flowId2, new RequestIdRequest("req-bc-42")).httpStatus())
                .isEqualTo(422);
        clock.setInstant(Instant.parse("2026-09-25T02:00:00Z"));
        assertThat(service.broadcast(flowId2, new RequestIdRequest("req-bc-42b")).httpStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("幂等：同键同参重放返回首次结果，同键异参返回 409 且状态不变")
    void idempotentReplayAndConflict() {
        insertProfile("13900000051", false, false, null);
        StoredResponse first = service.apply(new ApplyRequest("req-idem-1", "13900000051"));
        assertThat(first.httpStatus()).isEqualTo(200);
        long flowId = parseJson(first.body()).get("flowId").asLong();

        StoredResponse replay = service.apply(new ApplyRequest("req-idem-1", "13900000051"));
        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());

        StoredResponse conflict = service.apply(new ApplyRequest("req-idem-1", "13900000052"));
        assertThat(conflict.httpStatus()).isEqualTo(409);
        assertThat(parseJson(conflict.body()).get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(flowStatus(flowId)).isEqualTo("APPLIED");

        // 生效广播同键重放：返回首次结果，仅一条生效记录
        issueOk(flowId, "req-idem-code");
        confirmOk(flowId, "req-idem-confirm");
        clock.setInstant(WINDOW_TIME);
        StoredResponse broadcast = service.broadcast(flowId, new RequestIdRequest("req-idem-bc"));
        assertThat(broadcast.httpStatus()).isEqualTo(200);
        StoredResponse broadcastReplay = service.broadcast(flowId, new RequestIdRequest("req-idem-bc"));
        assertThat(broadcastReplay.httpStatus()).isEqualTo(200);
        assertThat(broadcastReplay.body()).isEqualTo(broadcast.body());
        assertThat(countEvents(flowId, "ACTIVATED")).isEqualTo(1);
    }

    @Test
    @DisplayName("广播幂等：同一流程不同 requestId 重复广播只产生一条生效记录")
    void broadcastRepeatedWithDifferentKeysProducesSingleActivation() {
        insertProfile("13900000061", false, false, null);
        long flowId = applyOk("13900000061", "req-apply-61");
        issueOk(flowId, "req-code-61");
        confirmOk(flowId, "req-confirm-61");
        clock.setInstant(WINDOW_TIME);

        assertThat(service.broadcast(flowId, new RequestIdRequest("req-bc-61a")).httpStatus())
                .isEqualTo(200);
        StoredResponse second = service.broadcast(flowId, new RequestIdRequest("req-bc-61b"));
        assertThat(second.httpStatus()).isEqualTo(200);
        assertThat(parseJson(second.body()).get("status").asText()).isEqualTo("ACTIVATED");
        assertThat(countEvents(flowId, "ACTIVATED")).isEqualTo(1);
    }

    @Test
    @DisplayName("撤销：生效前撤销为终态，撤销后不可生效，重复撤销幂等，生效后不可撤销")
    void cancelSemantics() {
        insertProfile("13900000071", false, false, null);
        long flowId = applyOk("13900000071", "req-apply-71");
        issueOk(flowId, "req-code-71");

        StoredResponse cancel = service.cancel(flowId, new RequestIdRequest("req-cancel-71"));
        assertThat(cancel.httpStatus()).isEqualTo(200);
        assertThat(flowStatus(flowId)).isEqualTo("CANCELLED");

        clock.setInstant(WINDOW_TIME);
        StoredResponse broadcast = service.broadcast(flowId, new RequestIdRequest("req-bc-71"));
        assertThat(broadcast.httpStatus()).isEqualTo(409);
        assertThat(parseJson(broadcast.body()).get("code").asText()).isEqualTo("FLOW_CANCELLED");

        StoredResponse cancelAgain = service.cancel(flowId, new RequestIdRequest("req-cancel-71b"));
        assertThat(cancelAgain.httpStatus()).isEqualTo(200);
        assertThat(countEvents(flowId, "CANCELLED")).isEqualTo(1);

        // 生效后不可撤销
        insertProfile("13900000072", false, false, null);
        long flowId2 = applyOk("13900000072", "req-apply-72");
        issueOk(flowId2, "req-code-72");
        confirmOk(flowId2, "req-confirm-72");
        clock.setInstant(WINDOW_TIME);
        assertThat(service.broadcast(flowId2, new RequestIdRequest("req-bc-72")).httpStatus())
                .isEqualTo(200);
        StoredResponse cancelLate = service.cancel(flowId2, new RequestIdRequest("req-cancel-72"));
        assertThat(cancelLate.httpStatus()).isEqualTo(409);
        assertThat(parseJson(cancelLate.body()).get("code").asText()).isEqualTo("FLOW_ALREADY_ACTIVATED");
        assertThat(flowStatus(flowId2)).isEqualTo("ACTIVATED");
    }

    @Test
    @DisplayName("同一号码已有在途流程时新申请返回 409；流程终态后可再次申请")
    void singleInFlightFlowPerNumber() {
        insertProfile("13900000081", false, false, null);
        long flowId = applyOk("13900000081", "req-apply-81");

        StoredResponse second = service.apply(new ApplyRequest("req-apply-81b", "13900000081"));
        assertThat(second.httpStatus()).isEqualTo(409);
        assertThat(parseJson(second.body()).get("code").asText()).isEqualTo("FLOW_IN_FLIGHT");

        service.cancel(flowId, new RequestIdRequest("req-cancel-81"));
        StoredResponse afterTerminal = service.apply(new ApplyRequest("req-apply-81c", "13900000081"));
        assertThat(afterTerminal.httpStatus()).isEqualTo(200);
    }

    private long applyOk(String msisdn, String requestId) {
        StoredResponse response = service.apply(new ApplyRequest(requestId, msisdn));
        assertThat(response.httpStatus()).as("申请应成功：%s", response.body()).isEqualTo(200);
        return parseJson(response.body()).get("flowId").asLong();
    }

    private void issueOk(long flowId, String requestId) {
        StoredResponse response = service.issueAuthCode(flowId, new RequestIdRequest(requestId));
        assertThat(response.httpStatus()).as("签码应成功：%s", response.body()).isEqualTo(200);
        assertThat(parseJson(response.body()).get("authCode").asText()).isNotBlank();
    }

    private void confirmOk(long flowId, String requestId) {
        StoredResponse response = service.donorConfirm(flowId, new RequestIdRequest(requestId));
        assertThat(response.httpStatus()).as("携出方确认应成功：%s", response.body()).isEqualTo(200);
    }
}
