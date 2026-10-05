package com.example.starter.forestry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.forestry.dto.AcceptanceRequest;
import com.example.starter.forestry.dto.ApplyRequest;
import com.example.starter.forestry.dto.ConfirmRequest;
import com.example.starter.forestry.dto.IssueRequest;
import com.example.starter.forestry.dto.LicenseView;
import com.example.starter.forestry.dto.ReviewRequest;
import com.example.starter.forestry.web.ApiException;

/**
 * requestId 幂等：指纹覆盖 requestId 与全部业务字段（含株数与蓄积设计值）。
 * 同键同参返回首次结果；同键异参 409 且不产生副作用、不占用限额；
 * 首次业务拒绝也作为首次结果重放。
 */
class IdempotencyTest extends AbstractForestryTest {

    private ApplyRequest apply(String requestId, String subplotId, int treeCount, String designVolume) {
        String species = "SP-001".equals(subplotId) ? "PINUS_MASSONIANA" : "CUNNINGHAMIA";
        return new ApplyRequest(requestId, "OP-I", subplotId, species, "MAIN",
                treeCount, new BigDecimal(designVolume));
    }

    @Test
    void sameKeySameParamsReturnsFirstApplication() {
        var first = service.apply(apply("idem-app-1", "SP-001", 20, "10.00"));
        var replay = service.apply(apply("idem-app-1", "SP-001", 20, "10.00"));

        assertThat(replay.id()).isEqualTo(first.id());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harvest_application WHERE request_id='idem-app-1'", Integer.class);
        assertThat(count).isOne();
    }

    @Test
    void sameKeyDifferentParamsReturns409WithoutSideEffect() {
        service.apply(apply("idem-app-2", "SP-001", 20, "10.00"));

        // 同键异参（株数与设计蓄积均变）：409
        assertThatThrownBy(() -> service.apply(apply("idem-app-2", "SP-001", 24, "12.00")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });

        // 仅有首次一张申请单
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harvest_application WHERE request_id='idem-app-2'", Integer.class);
        assertThat(count).isOne();
        // 未占用任何限额（双口径台账均为空）
        BigDecimal heldVolume = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger", BigDecimal.class);
        assertThat(heldVolume).isEqualByComparingTo("0.00");
        Long heldTrees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(trees),0) FROM quota_ledger", Long.class);
        assertThat(heldTrees).isZero();
    }

    @Test
    void rejectedApplyIsReplayedWithSameOutcome() {
        // 首次：禁伐区拒绝（422）
        assertThatThrownBy(() -> service.apply(
                new ApplyRequest("idem-app-3", "OP-I", "SP-005", "PINUS_MASSONIANA", "MAIN",
                        10, new BigDecimal("6.00"))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("PROTECTED_ZONE_FORBIDDEN");
                });

        // 同键同参重放：相同首次拒绝
        assertThatThrownBy(() -> service.apply(
                new ApplyRequest("idem-app-3", "OP-I", "SP-005", "PINUS_MASSONIANA", "MAIN",
                        10, new BigDecimal("6.00"))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("PROTECTED_ZONE_FORBIDDEN"));

        // 同键异参仍 409，不允许借同键“改道”
        assertThatThrownBy(() -> service.apply(
                new ApplyRequest("idem-app-3", "OP-I", "SP-001", "PINUS_MASSONIANA", "MAIN",
                        20, new BigDecimal("10.00"))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void issueAndConfirmReplayReturnFirstResult() {
        var app = service.apply(apply("idem-flow-app", "SP-001", 20, "10.00"));
        service.review(app.id(), new ReviewRequest(true, "OK"));

        LicenseView first = service.issue(new IssueRequest("idem-flow-issue", app.id()));
        LicenseView replay = service.issue(new IssueRequest("idem-flow-issue", app.id()));
        assertThat(replay.id()).isEqualTo(first.id());
        // 仅一笔占用台账
        Integer holds = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_ledger WHERE action='HOLD'", Integer.class);
        assertThat(holds).isOne();

        clock.setToday(LocalDate.of(2026, 4, 1));
        var confirmed = service.confirm(new ConfirmRequest("idem-flow-confirm", first.id()));
        var confirmReplay = service.confirm(new ConfirmRequest("idem-flow-confirm", first.id()));
        assertThat(confirmReplay.id()).isEqualTo(confirmed.id());
        assertThat(confirmReplay.status()).isEqualTo("HARVESTED");
    }

    @Test
    void acceptanceReplayReturnsFirstRenewalResult() {
        var app = service.apply(apply("idem-acc-app", "SP-001", 20, "10.00"));
        service.review(app.id(), new ReviewRequest(true, "OK"));
        LicenseView license = service.issue(new IssueRequest("idem-acc-issue", app.id()));
        clock.setToday(LocalDate.of(2026, 4, 1));
        service.confirm(new ConfirmRequest("idem-acc-confirm", license.id()));

        clock.setToday(LocalDate.of(2027, 5, 1));
        var first = service.acceptance(new AcceptanceRequest("idem-acc", app.id(), 90, 100));
        var replay = service.acceptance(new AcceptanceRequest("idem-acc", app.id(), 90, 100));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.status()).isEqualTo("PASSED");
    }
}
