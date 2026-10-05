package com.example.starter.forestry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.forestry.dto.ApplyRequest;
import com.example.starter.forestry.dto.IssueRequest;
import com.example.starter.forestry.dto.QuotaLedgerView;
import com.example.starter.forestry.dto.ReviewRequest;
import com.example.starter.forestry.web.ApiException;

/**
 * 株数与蓄积双口径限额：任一口径累计达限判满，恰达合法，超出整笔拒绝；
 * 双口径台账按树种档位输出，持有量可由明细复算且重复查询稳定。
 * 合成数据：马尾松 2026 年 100 株 / 100 立方米（株数口径更紧）；
 * SP-001 单株 0.50、SP-003 单株 0.10（抚育）。
 */
class DualQuotaLedgerTest extends AbstractForestryTest {

    private String prepareReviewed(String prefix, String subplotId, String harvestType,
                                   int treeCount, String designVolume) {
        var app = service.apply(new ApplyRequest(prefix + "-apply", "OP-DQ", subplotId,
                "PINUS_MASSONIANA", harvestType, treeCount, new BigDecimal(designVolume)));
        service.review(app.id(), new ReviewRequest(true, "OK"));
        return app.id();
    }

    @Test
    void exactlyReachingTreeQuotaIsLegal() {
        // 60 株 + 40 株 = 恰达 100 株上限；蓄积合计 34.00 远低于 100 立方米
        String app1 = prepareReviewed("dq-t-exact-1", "SP-001", "MAIN", 60, "30.00");
        String app2 = prepareReviewed("dq-t-exact-2", "SP-003", "TENDING", 40, "4.00");

        var lic1 = service.issue(new IssueRequest("dq-t-exact-issue1", app1));
        var lic2 = service.issue(new IssueRequest("dq-t-exact-issue2", app2));

        assertThat(lic1.status()).isEqualTo("ISSUED");
        assertThat(lic2.status()).isEqualTo("ISSUED");
        Long heldTrees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(trees),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA' AND quota_year=2026",
                Long.class);
        assertThat(heldTrees).isEqualTo(100L);
    }

    @Test
    void exceedingTreeQuotaRejectsWholeAndOccupiesNothing() {
        String app1 = prepareReviewed("dq-t-over-1", "SP-001", "MAIN", 60, "30.00");
        service.issue(new IssueRequest("dq-t-over-issue1", app1));

        // 60 + 41 = 101 株 > 100 株上限（蓄积 34.10 远低于 100 立方米）：株数口径判满
        String app2 = prepareReviewed("dq-t-over-2", "SP-003", "TENDING", 41, "4.10");
        assertThatThrownBy(() -> service.issue(new IssueRequest("dq-t-over-issue2", app2)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getErrorCode()).isEqualTo("QUOTA_EXCEEDED");
                });

        // 整笔拒绝：株数与蓄积台账均只保留首笔
        Long heldTrees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(trees),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA' AND quota_year=2026",
                Long.class);
        assertThat(heldTrees).isEqualTo(60L);
        BigDecimal heldVolume = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger WHERE species_code='PINUS_MASSONIANA' AND quota_year=2026",
                BigDecimal.class);
        assertThat(heldVolume).isEqualByComparingTo("30.00");
        Integer licenses = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harvest_license WHERE application_id=?", Integer.class, app2);
        assertThat(licenses).isZero();
    }

    @Test
    void quotaLedgerViewIsRecomputableAndStableAcrossQueries() {
        String app1 = prepareReviewed("dq-ledger-1", "SP-001", "MAIN", 60, "30.00");
        service.issue(new IssueRequest("dq-ledger-issue1", app1));
        String app2 = prepareReviewed("dq-ledger-2", "SP-003", "TENDING", 40, "4.00");
        service.issue(new IssueRequest("dq-ledger-issue2", app2));

        QuotaLedgerView first = service.quotaLedger("PINUS_MASSONIANA", 2026);
        QuotaLedgerView second = service.quotaLedger("PINUS_MASSONIANA", 2026);

        // 重复查询稳定：两次结果完全一致
        assertThat(second).isEqualTo(first);
        assertThat(first.totalTrees()).isEqualTo(100);
        assertThat(first.totalVolume()).isEqualByComparingTo("100.00");
        assertThat(first.heldTrees()).isEqualTo(100L);
        assertThat(first.heldVolume()).isEqualByComparingTo("34.00");

        // 可复算：持有量 = 台账明细逐笔合计
        long treesSum = first.entries().stream().mapToLong(QuotaLedgerView.Entry::trees).sum();
        BigDecimal volumeSum = first.entries().stream()
                .map(QuotaLedgerView.Entry::volume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(treesSum).isEqualTo(first.heldTrees());
        assertThat(volumeSum).isEqualByComparingTo(first.heldVolume());
        assertThat(first.entries()).allSatisfy(e -> assertThat(e.action()).isEqualTo("HOLD"));
    }

    @Test
    void quotaLedgerOfUnknownTierReturns404() {
        assertThatThrownBy(() -> service.quotaLedger("UNKNOWN_SPECIES", 2026))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getErrorCode()).isEqualTo("QUOTA_TIER_NOT_FOUND");
                });
    }
}
