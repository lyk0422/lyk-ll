package com.example.starter.pension.service;

import com.example.starter.pension.repo.InstallmentRepository.NewInstallment;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 分期排期与金额拆分。
 *
 * <p>各期按月初（UTC）到期：一次性为申请日当月 1 日的 1 期；12/24 期为申请月起连续
 * 各月 1 日。金额精确到分，前 n-1 期按整除均分，尾差（含余数）全部归入最后一期。</p>
 */
@Component
public class InstallmentScheduler {

    /**
     * 生成分期计划。
     *
     * @param totalCents 归属权益快照总额，单位：分
     * @param count      期数（1/12/24）
     * @param firstMonth 首期到期月（通常为申请日所在月，UTC）
     * @return 不可变的期次建账参数列表
     */
    public List<NewInstallment> schedule(long totalCents, int count, LocalDate firstMonth) {
        if (count <= 0) {
            throw new IllegalArgumentException("期数必须为正: " + count);
        }
        if (totalCents < 0) {
            throw new IllegalArgumentException("分期总额不能为负: " + totalCents);
        }
        LocalDate start = firstMonth.withDayOfMonth(1);
        long base = totalCents / count;
        List<NewInstallment> result = new ArrayList<>(count);
        long allocated = 0L;
        for (int i = 1; i <= count; i++) {
            LocalDate due = start.plusMonths(i - 1L);
            long amount = i == count ? totalCents - allocated : base;
            allocated += amount;
            result.add(new NewInstallment(i, due, amount));
        }
        return List.copyOf(result);
    }
}
