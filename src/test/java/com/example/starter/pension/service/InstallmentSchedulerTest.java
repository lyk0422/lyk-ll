package com.example.starter.pension.service;

import com.example.starter.pension.repo.InstallmentRepository.NewInstallment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分期排期与尾差单元测试：尾差归入最后一期，各期按月初到期，总额等于快照金额。
 */
class InstallmentSchedulerTest {

    private final InstallmentScheduler scheduler = new InstallmentScheduler();

    @Test
    @DisplayName("100 分分 12 期：前 11 期各 8 分，尾差 4 分归入最后一期")
    void remainderGoesToLastInstallment() {
        List<NewInstallment> schedule = scheduler.schedule(
                100L, 12, LocalDate.of(2026, 9, 25));

        assertThat(schedule).hasSize(12);
        for (int i = 0; i < 11; i++) {
            assertThat(schedule.get(i).amountCents()).isEqualTo(8L);
        }
        assertThat(schedule.get(11).amountCents()).isEqualTo(12L);
        assertThat(schedule.stream().mapToLong(NewInstallment::amountCents).sum())
                .isEqualTo(100L);
    }

    @Test
    @DisplayName("10 分分 24 期：前 23 期为 0，尾差 10 分全部归入最后一期")
    void tinyAmountLastInstallmentOnly() {
        List<NewInstallment> schedule = scheduler.schedule(
                10L, 24, LocalDate.of(2026, 1, 15));

        assertThat(schedule).hasSize(24);
        for (int i = 0; i < 23; i++) {
            assertThat(schedule.get(i).amountCents()).isZero();
        }
        assertThat(schedule.get(23).amountCents()).isEqualTo(10L);
        assertThat(schedule.stream().mapToLong(NewInstallment::amountCents).sum())
                .isEqualTo(10L);
    }

    @Test
    @DisplayName("各期按月初 UTC 到期，一次性为申请月月初的 1 期")
    void dueDatesAreMonthStarts() {
        List<NewInstallment> lump = scheduler.schedule(
                500L, 1, LocalDate.of(2026, 9, 30));
        assertThat(lump).hasSize(1);
        assertThat(lump.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(lump.get(0).amountCents()).isEqualTo(500L);

        List<NewInstallment> monthly = scheduler.schedule(
                2400L, 12, LocalDate.of(2026, 1, 31));
        assertThat(monthly.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(monthly.get(11).dueDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(monthly.stream().mapToLong(NewInstallment::amountCents).sum())
                .isEqualTo(2400L);
    }
}
