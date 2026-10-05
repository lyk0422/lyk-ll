package com.example.starter.forestry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.forestry.dto.LicenseView;
import com.example.starter.forestry.dto.QuotaLedgerView;

/**
 * 双口径限额台账输出：按树种档位给出限额/占用/剩余（株数与蓄积），
 * 占用量可由台账流水复算，重复查询结果稳定。
 */
class QuotaLedgerViewTest extends AbstractForestryTest {

    private QuotaLedgerView tierOf(List<QuotaLedgerView> ledger, String speciesCode) {
        return ledger.stream().filter(v -> v.speciesCode().equals(speciesCode)).findFirst().orElseThrow();
    }

    @Test
    void emptyLedgerReportsFullRemainingForAllTiers() {
        List<QuotaLedgerView> ledger = service.quotaLedger(2026);

        // 按树种编码排序，三个合成档位齐全
        assertThat(ledger).extracting(QuotaLedgerView::speciesCode)
                .containsExactly("CUNNINGHAMIA", "PINUS_MASSONIANA", "QUERCUS");
        QuotaLedgerView pinus = tierOf(ledger, "PINUS_MASSONIANA");
        assertThat(pinus.totalTrees()).isEqualTo(250);
        assertThat(pinus.totalVolume()).isEqualByComparingTo("100.00");
        assertThat(pinus.heldTrees()).isZero();
        assertThat(pinus.heldVolume()).isEqualByComparingTo("0.00");
        assertThat(pinus.remainingTrees()).isEqualTo(250);
        assertThat(pinus.remainingVolume()).isEqualByComparingTo("100.00");
    }

    @Test
    void ledgerReflectsHoldsAndIsRecomputableAndStable() {
        // 栎类 2026-03-01 发证（2026-05-30 到期）；杉木 2026-04-15 发证（2026-07-14 到期）
        LicenseView quercus = issueLicense("lv-1", "OP-L", "SP-007", "QUERCUS", "MAIN",
                50, new BigDecimal("20.00"));
        clock.setToday(LocalDate.of(2026, 4, 15));
        issueLicense("lv-2", "OP-L", "SP-002", "CUNNINGHAMIA", "MAIN", 120, new BigDecimal("30.00"));

        List<QuotaLedgerView> first = service.quotaLedger(2026);

        QuotaLedgerView cunninghamia = tierOf(first, "CUNNINGHAMIA");
        assertThat(cunninghamia.heldTrees()).isEqualTo(120);
        assertThat(cunninghamia.heldVolume()).isEqualByComparingTo("30.00");
        assertThat(cunninghamia.remainingTrees()).isEqualTo(80);
        assertThat(cunninghamia.remainingVolume()).isEqualByComparingTo("20.00");

        QuotaLedgerView quercusView = tierOf(first, "QUERCUS");
        assertThat(quercusView.heldTrees()).isEqualTo(50);
        assertThat(quercusView.heldVolume()).isEqualByComparingTo("20.00");
        assertThat(quercusView.remainingTrees()).isEqualTo(40);
        assertThat(quercusView.remainingVolume()).isEqualByComparingTo("20.00");

        // 可复算：占用量与台账流水汇总一致
        Integer heldTrees = jdbc.queryForObject(
                "SELECT COALESCE(SUM(tree_count),0) FROM quota_ledger WHERE species_code='QUERCUS' AND quota_year=2026",
                Integer.class);
        BigDecimal heldVolume = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume),0) FROM quota_ledger WHERE species_code='QUERCUS' AND quota_year=2026",
                BigDecimal.class);
        assertThat(quercusView.heldTrees()).isEqualTo(heldTrees);
        assertThat(quercusView.heldVolume()).isEqualByComparingTo(heldVolume);

        // 重复查询稳定
        List<QuotaLedgerView> second = service.quotaLedger(2026);
        assertThat(second).isEqualTo(first);

        // 栎类证件逾期作废后双口径当日释放，台账回到零占用；杉木未到期不受影响
        clock.setToday(LocalDate.of(2026, 6, 1));
        assertThat(service.processExpiredLicenses()).isEqualTo(1);
        QuotaLedgerView afterRelease = tierOf(service.quotaLedger(2026), "QUERCUS");
        assertThat(afterRelease.heldTrees()).isZero();
        assertThat(afterRelease.heldVolume()).isEqualByComparingTo("0.00");
        assertThat(afterRelease.remainingTrees()).isEqualTo(90);
        assertThat(afterRelease.remainingVolume()).isEqualByComparingTo("40.00");
        assertThat(tierOf(service.quotaLedger(2026), "CUNNINGHAMIA").heldTrees()).isEqualTo(120);
    }
}
