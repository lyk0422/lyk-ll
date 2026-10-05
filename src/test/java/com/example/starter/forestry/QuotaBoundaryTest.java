package com.example.starter.forestry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.forestry.dto.ApplyRequest;
import com.example.starter.forestry.dto.IssueRequest;
import com.example.starter.forestry.dto.LicenseView;
import com.example.starter.forestry.dto.ReviewRequest;
import com.example.starter.forestry.web.ApiException;

/**
 * 年度限额分档控制：恰达限额合法、超出整笔拒绝且不占用、逾期释放后额度可复用。
 * 合成数据：杉木 CUNNINGHAMIA 2026 年限额 200 株 / 50 立方米（单株蓄积 0.25，双口径等比）；
 * 可用成熟小班 SP-002、SP-006。
 */
class QuotaBoundaryTest extends AbstractForestryTest {

    private LicenseView prepare(String prefix, String subplotId, int treeCount, String designedVolume) {
        var app = service.apply(new ApplyRequest(prefix + "-apply", "OP-Q", subplotId,
                "CUNNINGHAMIA", "MAIN", treeCount, new BigDecimal(designedVolume)));
        service.review(app.id(), new ReviewRequest(true, "OK"));
        return service.issue(new IssueRequest(prefix + "-issue", app.id()));
    }

    @Test
    void exactlyReachingQuotaIsLegal() {
        LicenseView first = prepare("q-exact-1", "SP-002", 120, "30.00");
        LicenseView second = prepare("q-exact-2", "SP-006", 80, "20.00");

        assertThat(first.status()).isEqualTo("ISSUED");
        assertThat(second.status()).isEqualTo("ISSUED");

        BigDecimal held = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger WHERE species_code='CUNNINGHAMIA' AND quota_year=2026",
                BigDecimal.class);
        assertThat(held).isEqualByComparingTo("50.00");
    }

    @Test
    void exceedingQuotaRejectsWholeApplicationAndOccupiesNothing() {
        prepare("q-over-1", "SP-002", 120, "30.00");

        var app = service.apply(new ApplyRequest("q-over-2-apply", "OP-Q", "SP-006",
                "CUNNINGHAMIA", "MAIN", 81, new BigDecimal("20.25")));
        service.review(app.id(), new ReviewRequest(true, "OK"));

        assertThatThrownBy(() -> service.issue(new IssueRequest("q-over-2-issue", app.id())))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("QUOTA_EXCEEDED");
                });

        // 超限额整笔拒绝：台账仍为 30，未产生采伐证
        BigDecimal held = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger WHERE species_code='CUNNINGHAMIA' AND quota_year=2026",
                BigDecimal.class);
        assertThat(held).isEqualByComparingTo("30.00");
        Integer licenses = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harvest_license WHERE application_id=?", Integer.class, app.id());
        assertThat(licenses).isZero();
    }

    @Test
    void quotaReleasedOnExpiryCanBeReused() {
        LicenseView license = prepare("q-rel-1", "SP-002", 200, "50.00");

        // 逾期扫描：证件作废，限额当日（2026-05-31）释放
        clock.setToday(LocalDate.of(2026, 5, 31));
        int voided = service.processExpiredLicenses();
        assertThat(voided).isEqualTo(1);

        BigDecimal held = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger WHERE species_code='CUNNINGHAMIA' AND quota_year=2026",
                BigDecimal.class);
        assertThat(held).isEqualByComparingTo("0.00");

        // 释放后同档位新申请可再次占用全部 50
        clock.setToday(LocalDate.of(2026, 6, 1));
        LicenseView second = prepare("q-rel-2", "SP-006", 200, "50.00");
        assertThat(second.status()).isEqualTo("ISSUED");
        assertThat(second.id()).isNotEqualTo(license.id());
    }
}
