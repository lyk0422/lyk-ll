package com.example.starter.forestry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.forestry.dto.ApplyRequest;
import com.example.starter.forestry.dto.IssueRequest;
import com.example.starter.forestry.dto.LicenseView;
import com.example.starter.forestry.dto.ReviewRequest;
import com.example.starter.forestry.web.ApiException;

/**
 * 株数与蓄积双口径额度控制：任一口径使累计量达到限额即判满，恰达合法，超出整笔拒绝。
 * 合成数据：栎类 QUERCUS 2026 限额 90 株 / 40 立方米（株数口径先判满，单株蓄积 0.4）；
 * 马尾松 PINUS_MASSONIANA 2026 限额 250 株 / 100 立方米（蓄积口径先判满，单株蓄积 0.5）。
 */
class DualQuotaBoundaryTest extends AbstractForestryTest {

    private LicenseView prepare(String prefix, String operator, String subplotId,
                                String species, String harvestType, int treeCount, String designedVolume) {
        var app = service.apply(new ApplyRequest(prefix + "-apply", operator, subplotId,
                species, harvestType, treeCount, new BigDecimal(designedVolume)));
        service.review(app.id(), new ReviewRequest(true, "OK"));
        return service.issue(new IssueRequest(prefix + "-issue", app.id()));
    }

    @Test
    void treeCountExactlyReachingQuotaIsLegal() {
        // 株数恰达 90（50 + 40），蓄积 36 < 40：株数口径恰达合法
        LicenseView first = prepare("dq-texact-1", "OP-DQ", "SP-007", "QUERCUS", "MAIN", 50, "20.00");
        LicenseView second = prepare("dq-texact-2", "OP-DQ", "SP-008", "QUERCUS", "MAIN", 40, "16.00");

        assertThat(first.status()).isEqualTo("ISSUED");
        assertThat(second.status()).isEqualTo("ISSUED");
        Integer heldTrees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(tree_count),0) FROM quota_ledger WHERE species_code='QUERCUS' AND quota_year=2026",
                Integer.class);
        assertThat(heldTrees).isEqualTo(90);
    }

    @Test
    void treeCountExceededWhileVolumeFitsRejectsWhole() {
        prepare("dq-tover-1", "OP-DQ", "SP-007", "QUERCUS", "MAIN", 50, "20.00");
        prepare("dq-tover-2", "OP-DQ", "SP-008", "QUERCUS", "MAIN", 40, "16.00");

        // 第三笔 10 株 / 4.00：蓄积累计恰达 40 合法，但株数 100 > 90，株数口径判满，整笔拒绝
        var app = service.apply(new ApplyRequest("dq-tover-3-apply", "OP-DQ", "SP-007",
                "QUERCUS", "MAIN", 10, new BigDecimal("4.00")));
        service.review(app.id(), new ReviewRequest(true, "OK"));

        assertThatThrownBy(() -> service.issue(new IssueRequest("dq-tover-3-issue", app.id())))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("QUOTA_EXCEEDED");
                });

        // 整笔拒绝：株数与蓄积台账均未变化，未产生采伐证
        Integer heldTrees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(tree_count),0) FROM quota_ledger WHERE species_code='QUERCUS' AND quota_year=2026",
                Integer.class);
        assertThat(heldTrees).isEqualTo(90);
        BigDecimal heldVolume = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger WHERE species_code='QUERCUS' AND quota_year=2026",
                BigDecimal.class);
        assertThat(heldVolume).isEqualByComparingTo("36.00");
        Integer licenses = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harvest_license WHERE application_id=?", Integer.class, app.id());
        assertThat(licenses).isZero();
    }

    @Test
    void volumeExceededWhileTreeCountFitsRejectsWhole() {
        // 马尾松 180 株 / 90.00：株数 180 <= 250，蓄积占用 90
        prepare("dq-vover-1", "OP-DQ", "SP-001", "PINUS_MASSONIANA", "MAIN", 180, "90.00");

        // 第二笔 40 株 / 20.00（幼龄林抚育伐）：株数累计 220 <= 250 合法，蓄积 110 > 100，蓄积口径判满
        var app = service.apply(new ApplyRequest("dq-vover-2-apply", "OP-DQ", "SP-003",
                "PINUS_MASSONIANA", "TENDING", 40, new BigDecimal("20.00")));
        service.review(app.id(), new ReviewRequest(true, "OK"));

        assertThatThrownBy(() -> service.issue(new IssueRequest("dq-vover-2-issue", app.id())))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("QUOTA_EXCEEDED");
                });

        Integer heldTrees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(tree_count),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA' AND quota_year=2026",
                Integer.class);
        assertThat(heldTrees).isEqualTo(180);
        BigDecimal heldVolume = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA' AND quota_year=2026",
                BigDecimal.class);
        assertThat(heldVolume).isEqualByComparingTo("90.00");
    }

    @Test
    void volumeExactlyReachingQuotaIsLegal() {
        // 蓄积恰达 100 立方米，株数 200 <= 250：蓄积口径恰达合法
        LicenseView license = prepare("dq-vexact", "OP-DQ", "SP-001", "PINUS_MASSONIANA",
                "MAIN", 200, "100.00");

        assertThat(license.status()).isEqualTo("ISSUED");
        BigDecimal heldVolume = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA' AND quota_year=2026",
                BigDecimal.class);
        assertThat(heldVolume).isEqualByComparingTo("100.00");
    }
}
