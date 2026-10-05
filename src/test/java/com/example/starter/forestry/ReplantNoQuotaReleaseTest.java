package com.example.starter.forestry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.example.starter.forestry.dto.AcceptanceRequest;
import com.example.starter.forestry.dto.ConfirmRequest;
import com.example.starter.forestry.dto.LicenseView;

/**
 * 补植验收合格后，补植株数不计回限额释放：更新验收（含补植合格）不产生任何
 * 限额释放台账，株数与蓄积双口径占用保持不变。
 */
class ReplantNoQuotaReleaseTest extends AbstractForestryTest {

    @Test
    void passedReplantAcceptanceDoesNotReleaseQuota() {
        LicenseView license = issueLicense("rp-1", "OP-RP", "SP-001",
                "PINUS_MASSONIANA", "MAIN", 30, new BigDecimal("15.00"));
        clock.setToday(LocalDate.of(2026, 4, 1));
        service.confirm(new ConfirmRequest("rp-1-confirm", license.id()));

        // 首次验收不合格进入补植，再补植验收合格
        clock.setToday(LocalDate.of(2027, 6, 1));
        service.acceptance(new AcceptanceRequest("rp-1-acc1", license.applicationId(), 80, 100));
        clock.setToday(LocalDate.of(2027, 9, 1));
        var renewal = service.acceptance(new AcceptanceRequest("rp-1-acc2", license.applicationId(), 90, 100));
        assertThat(renewal.status()).isEqualTo("PASSED");

        // 补植合格不释放限额：无任何 RELEASE 台账，双口径占用保持证载量
        Integer releases = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_ledger WHERE action='RELEASE'", Integer.class);
        assertThat(releases).isZero();
        Integer heldTrees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(tree_count),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA' AND quota_year=2026",
                Integer.class);
        assertThat(heldTrees).isEqualTo(30);
        BigDecimal heldVolume = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA' AND quota_year=2026",
                BigDecimal.class);
        assertThat(heldVolume).isEqualByComparingTo("15.00");
    }

    @Test
    void firstPassAcceptanceDoesNotReleaseQuotaEither() {
        LicenseView license = issueLicense("rp-2", "OP-RP2", "SP-001",
                "PINUS_MASSONIANA", "MAIN", 30, new BigDecimal("15.00"));
        clock.setToday(LocalDate.of(2026, 4, 1));
        service.confirm(new ConfirmRequest("rp-2-confirm", license.id()));

        clock.setToday(LocalDate.of(2027, 6, 1));
        var renewal = service.acceptance(new AcceptanceRequest("rp-2-acc", license.applicationId(), 90, 100));
        assertThat(renewal.status()).isEqualTo("PASSED");

        Integer releases = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quota_ledger WHERE action='RELEASE'", Integer.class);
        assertThat(releases).isZero();
        Integer heldTrees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(tree_count),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA' AND quota_year=2026",
                Integer.class);
        assertThat(heldTrees).isEqualTo(30);
    }
}
