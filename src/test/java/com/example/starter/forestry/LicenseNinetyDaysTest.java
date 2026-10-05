package com.example.starter.forestry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.forestry.dto.ConfirmRequest;
import com.example.starter.forestry.dto.LicenseView;
import com.example.starter.forestry.web.ApiException;

/**
 * 采伐期限 90 个自然日（UTC 日界）：发证 2026-03-01，截止 2026-05-30（恰第 90 日）。
 * 恰在第 90 日确认有效；第 91 日确认触发证件作废且限额当日释放。
 */
class LicenseNinetyDaysTest extends AbstractForestryTest {

    private LicenseView issue() {
        return issueLicense("d90", "OP-D", "SP-001", "PINUS_MASSONIANA", "MAIN",
                40, new BigDecimal("20.00"));
    }

    @Test
    void confirmationExactlyOnDay90IsValid() {
        LicenseView license = issue();
        assertThat(license.issueDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(license.expireDate()).isEqualTo(LocalDate.of(2026, 5, 30));

        clock.setToday(LocalDate.of(2026, 5, 30));
        LicenseView confirmed = service.confirm(new ConfirmRequest("d90-confirm", license.id()));

        assertThat(confirmed.status()).isEqualTo("HARVESTED");
        assertThat(confirmed.harvestDate()).isEqualTo(LocalDate.of(2026, 5, 30));
        // 采伐确认后生成迹地更新义务，期限为翌年 12 月 31 日
        var renewal = service.getRenewal(license.applicationId());
        assertThat(renewal.status()).isEqualTo("PENDING");
        assertThat(renewal.deadline()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void confirmationOnDay91VoidsLicenseAndReleasesQuotaSameDay() {
        LicenseView license = issue();

        clock.setToday(LocalDate.of(2026, 5, 31));
        assertThatThrownBy(() -> service.confirm(new ConfirmRequest("d90-late", license.id())))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("LICENSE_EXPIRED");
                });

        LicenseView reloaded = service.getLicense(license.id());
        assertThat(reloaded.status()).isEqualTo("VOID");
        // 限额当日释放：台账净额归零
        BigDecimal held = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA' AND quota_year=2026",
                BigDecimal.class);
        assertThat(held).isEqualByComparingTo("0.00");
        // 逾期释放记账日恰为当日
        Integer releaseRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_ledger WHERE license_id=? AND action='RELEASE' AND created_date=?",
                Integer.class, license.id(), java.sql.Date.valueOf(LocalDate.of(2026, 5, 31)));
        assertThat(releaseRows).isEqualTo(1);
        // 申请单随证件关闭
        assertThat(service.getApplication(license.applicationId()).status()).isEqualTo("LICENSE_VOID");
    }

    @Test
    void expiredLicenseCannotBeConfirmedAfterScheduledVoid() {
        LicenseView license = issue();

        clock.setToday(LocalDate.of(2026, 5, 31));
        assertThat(service.processExpiredLicenses()).isEqualTo(1);

        assertThatThrownBy(() -> service.confirm(new ConfirmRequest("d90-after-scan", license.id())))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo("LICENSE_VOID"));
    }
}
