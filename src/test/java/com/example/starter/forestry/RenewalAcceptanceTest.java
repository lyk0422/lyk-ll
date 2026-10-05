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
 * 迹地更新联动：合格率 85% 边界、首次期限翌年 12-31、一次补植机会与 6 个月月末期限、
 * 补植仍不合格列入限批名单且期限内新申请一律拒绝。
 */
class RenewalAcceptanceTest extends AbstractForestryTest {

    /** 走到采伐确认，时钟停在 2026-04-01（更新首次期限 2027-12-31）。 */
    private String harvestedApplication(String operatorId, String prefix) {
        var app = service.apply(new ApplyRequest(prefix + "-apply", operatorId, "SP-001",
                "PINUS_MASSONIANA", "MAIN", 30, new BigDecimal("15.00")));
        service.review(app.id(), new ReviewRequest(true, "OK"));
        LicenseView license = service.issue(new IssueRequest(prefix + "-issue", app.id()));
        clock.setToday(LocalDate.of(2026, 4, 1));
        service.confirm(new ConfirmRequest(prefix + "-confirm", license.id()));
        return app.id();
    }

    @Test
    void qualifiedRateExactly85PercentPasses() {
        String appId = harvestedApplication("OP-R1", "r-85");
        clock.setToday(LocalDate.of(2027, 6, 1));

        var renewal = service.acceptance(new AcceptanceRequest("r-85-acc", appId, 85, 100));

        assertThat(renewal.status()).isEqualTo("PASSED");
        assertThat(renewal.qualifiedRate()).isEqualByComparingTo(new BigDecimal("0.8500"));
        assertThat(service.getApplication(appId).status()).isEqualTo("RENEWAL_DONE");
    }

    @Test
    void qualifiedRate84PercentTriggersOneReplantChance() {
        String appId = harvestedApplication("OP-R2", "r-84");
        clock.setToday(LocalDate.of(2027, 6, 1));

        var renewal = service.acceptance(new AcceptanceRequest("r-84-acc", appId, 84, 100));

        assertThat(renewal.status()).isEqualTo("REPLANTING");
        // 补植期限：2027-06-01 起 6 个月所在月月末 = 2027-12-31（含当日）
        assertThat(renewal.replantDeadline()).isEqualTo(LocalDate.of(2027, 12, 31));
        // 尚未限批、申请未关闭
        assertThat(service.getApplication(appId).status()).isEqualTo("HARVESTED");
    }

    @Test
    void replantExactlyAtMonthEndDeadlinePasses() {
        String appId = harvestedApplication("OP-R3", "r-replant-exact");
        clock.setToday(LocalDate.of(2027, 6, 1));
        service.acceptance(new AcceptanceRequest("r-replant-exact-acc1", appId, 80, 100));

        // 恰满 6 个月月末（2027-12-31）补植验收合格
        clock.setToday(LocalDate.of(2027, 12, 31));
        var renewal = service.acceptance(new AcceptanceRequest("r-replant-exact-acc2", appId, 90, 100));

        assertThat(renewal.status()).isEqualTo("PASSED");
        assertThat(renewal.replantAcceptedAt()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void replantAfterDeadlineRejected() {
        String appId = harvestedApplication("OP-R4", "r-replant-late");
        clock.setToday(LocalDate.of(2027, 6, 1));
        service.acceptance(new AcceptanceRequest("r-replant-late-acc1", appId, 80, 100));

        clock.setToday(LocalDate.of(2028, 1, 1));
        assertThatThrownBy(() -> service.acceptance(
                new AcceptanceRequest("r-replant-late-acc2", appId, 100, 100)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("REPLANT_DEADLINE_PASSED"));
    }

    @Test
    void failedReplantPutsOperatorOnRestrictionListAndBlocksNewApplications() {
        String appId = harvestedApplication("OP-R5", "r-fail");
        clock.setToday(LocalDate.of(2027, 6, 1));
        service.acceptance(new AcceptanceRequest("r-fail-acc1", appId, 80, 100));

        // 补植仍不合格：列入限批，期限 1 年（2027-06-01 至 2028-06-01，含当日）
        var renewal = service.acceptance(new AcceptanceRequest("r-fail-acc2", appId, 70, 100));
        assertThat(renewal.status()).isEqualTo("FAILED");
        assertThat(service.getApplication(appId).status()).isEqualTo("RENEWAL_FAILED");

        // 限批期内（含截止当日）新申请一律拒绝
        clock.setToday(LocalDate.of(2028, 6, 1));
        assertThatThrownBy(() -> service.apply(new ApplyRequest("r-fail-new", "OP-R5", "SP-002",
                "CUNNINGHAMIA", "MAIN", 10, new BigDecimal("4.00"))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getErrorCode()).isEqualTo("OPERATOR_RESTRICTED");
                });

        // 限批结束次日恢复申请资格
        clock.setToday(LocalDate.of(2028, 6, 2));
        var newApp = service.apply(new ApplyRequest("r-fail-new2", "OP-R5", "SP-002",
                "CUNNINGHAMIA", "MAIN", 10, new BigDecimal("4.00")));
        assertThat(newApp.status()).isEqualTo("SUBMITTED");
    }

    @Test
    void firstAcceptanceAfterDeadlineRejected() {
        String appId = harvestedApplication("OP-R6", "r-deadline");
        clock.setToday(LocalDate.of(2028, 1, 1));
        assertThatThrownBy(() -> service.acceptance(
                new AcceptanceRequest("r-deadline-acc", appId, 100, 100)))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("RENEWAL_DEADLINE_PASSED"));
    }

    @Test
    void passedReplantAcceptanceDoesNotReleaseQuota() {
        // 马尾松 2026：发证占用 30 株 / 15.00 立方米
        String appId = harvestedApplication("OP-R7", "r-norelease");
        clock.setToday(LocalDate.of(2027, 6, 1));
        service.acceptance(new AcceptanceRequest("r-norelease-acc1", appId, 80, 100));

        // 补植验收合格：更新义务通过
        clock.setToday(LocalDate.of(2027, 9, 1));
        var renewal = service.acceptance(new AcceptanceRequest("r-norelease-acc2", appId, 95, 100));
        assertThat(renewal.status()).isEqualTo("PASSED");
        assertThat(service.getApplication(appId).status()).isEqualTo("RENEWAL_DONE");

        // 补植株数不计回限额释放：台账无任何 RELEASE，双口径占用保持发证时的 30 株 / 15.00
        Integer releases = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_ledger WHERE action='RELEASE'", Integer.class);
        assertThat(releases).isZero();
        Long heldTrees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(trees),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA' AND quota_year=2026",
                Long.class);
        assertThat(heldTrees).isEqualTo(30L);
        BigDecimal heldVolume = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA' AND quota_year=2026",
                BigDecimal.class);
        assertThat(heldVolume).isEqualByComparingTo("15.00");
    }
}
