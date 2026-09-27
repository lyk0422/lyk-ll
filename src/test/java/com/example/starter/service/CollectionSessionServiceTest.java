package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.api.dto.CollectionSessionView;
import com.example.starter.support.ApiException;
import com.example.starter.testsupport.AbstractServiceTest;
import com.example.starter.testsupport.TestRequests;

/**
 * 采集会话创建：绑定工序卡、工具日校验版本与紧固点清单快照；
 * 版本从 1 开始；失败不占幂等键。
 */
class CollectionSessionServiceTest extends AbstractServiceTest {

    @Autowired
    private CollectionService collectionService;

    @Autowired
    private OperationService operationService;

    private void dispatchCard(String cardNo, String station) {
        operationService.dispatch(TestRequests.dispatch("d-" + cardNo, cardNo, station, "N"));
    }

    @Test
    @DisplayName("创建会话主流程：201，绑定工具校验版本并快照紧固点清单，水位线 0、版本 1")
    void createSessionMainFlow() {
        operationService.registerTool(TestRequests.tool("TW-1", TestRequests.TODAY));
        dispatchCard("CARD-C1", "S-C1");

        Outcome outcome = collectionService.createSession(
                TestRequests.createSession("cs-1", "SES-1", "CARD-C1", "TW-1"));
        assertThat(outcome.status()).isEqualTo(201);

        CollectionSessionView view = (CollectionSessionView) outcome.body();
        assertThat(view.sessionNo()).isEqualTo("SES-1");
        assertThat(view.cardNo()).isEqualTo("CARD-C1");
        assertThat(view.toolCode()).isEqualTo("TW-1");
        assertThat(view.calibrationDate()).isEqualTo(TestRequests.TODAY);
        assertThat(view.status()).isEqualTo("OPEN");
        assertThat(view.watermark()).isZero();
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.points()).containsExactly("P1", "P2");
        assertThat(view.bufferedCount()).isZero();
        assertThat(view.consumedCount()).isZero();
        assertThat(view.closedAt()).isNull();
    }

    @Test
    @DisplayName("工序卡不存在 404；工具未登记 422；卡非派工态 422")
    void createSessionFailures() {
        operationService.registerTool(TestRequests.tool("TW-1", TestRequests.TODAY));

        // 卡不存在 → 404
        assertThatThrownBy(() -> collectionService.createSession(
                TestRequests.createSession("cs-404", "SES-X", "CARD-GHOST", "TW-1")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(404);
                    assertThat(e.getErrorCode()).isEqualTo("CARD_NOT_FOUND");
                });

        dispatchCard("CARD-C2", "S-C2");
        // 工具未登记 → 422
        assertThatThrownBy(() -> collectionService.createSession(
                TestRequests.createSession("cs-tool", "SES-2", "CARD-C2", "TW-GHOST")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getErrorCode()).isEqualTo("TOOL_NOT_REGISTERED");
                });

        // 卡离开派工态（全部点力矩合格 → TIGHTENED）→ 422
        operationService.measure(TestRequests.torque("m-c2-1", "CARD-C2", "P1", "100", "op1", "TW-1"));
        operationService.measure(TestRequests.torque("m-c2-2", "CARD-C2", "P2", "50", "op1", "TW-1"));
        assertThat(operationService.getCard("CARD-C2").status()).isEqualTo("TIGHTENED");
        assertThatThrownBy(() -> collectionService.createSession(
                TestRequests.createSession("cs-state", "SES-3", "CARD-C2", "TW-1")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getErrorCode()).isEqualTo("CARD_NOT_IN_TIGHTENING");
                });
    }

    @Test
    @DisplayName("会话号重复（异 requestId）409；同 requestId 同参重放首次结果且不重复建会话")
    void createSessionIdempotency() {
        operationService.registerTool(TestRequests.tool("TW-1", TestRequests.TODAY));
        dispatchCard("CARD-C3", "S-C3");

        Outcome first = collectionService.createSession(
                TestRequests.createSession("cs-idem", "SES-4", "CARD-C3", "TW-1"));
        assertThat(first.status()).isEqualTo(201);

        // 同键同参：重放首次冻结结果
        Outcome replay = collectionService.createSession(
                TestRequests.createSession("cs-idem", "SES-4", "CARD-C3", "TW-1"));
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.replay()).isTrue();
        assertThat(((CollectionSessionView) replay.body()).sessionNo()).isEqualTo("SES-4");

        // 同键异参：409
        Outcome conflict = collectionService.createSession(
                TestRequests.createSession("cs-idem", "SES-4-CHANGED", "CARD-C3", "TW-1"));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(((java.util.Map<?, ?>) conflict.body()).get("errorCode"))
                .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");

        // 异键同会话号：409 会话号已存在
        assertThatThrownBy(() -> collectionService.createSession(
                TestRequests.createSession("cs-idem-2", "SES-4", "CARD-C3", "TW-1")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getErrorCode()).isEqualTo("SESSION_NO_EXISTS");
                });

        // 全库仅一个会话
        Integer sessions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM collection_session", Integer.class);
        assertThat(sessions).isEqualTo(1);
    }

    @Test
    @DisplayName("失败不占键：工具未登记失败后，同 requestId 修正参数可重新执行成功")
    void failureDoesNotOccupyKey() {
        operationService.registerTool(TestRequests.tool("TW-1", TestRequests.TODAY));
        dispatchCard("CARD-C4", "S-C4");

        // 首次：工具未登记 → 422，不占键
        assertThatThrownBy(() -> collectionService.createSession(
                TestRequests.createSession("cs-retry", "SES-5", "CARD-C4", "TW-LATE")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(422));

        // 登记工具后同键（异参，因首次未占键故不判异参冲突）重试成功
        operationService.registerTool(TestRequests.tool("TW-LATE", TestRequests.TODAY));
        Outcome retried = collectionService.createSession(
                TestRequests.createSession("cs-retry", "SES-5", "CARD-C4", "TW-LATE"));
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.replay()).isFalse();
    }
}
