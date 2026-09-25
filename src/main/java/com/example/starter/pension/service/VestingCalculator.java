package com.example.starter.pension.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 权益归属与分期金额纯计算逻辑。
 *
 * <p>司龄按入职日至支付申请日（UTC 日界）的整年数计算；企业缴费阶梯：
 * 满 3 年 50%、满 5 年 80%、满 8 年 100%，恰满档位年限按该档归属，不足 3 年为 0%。
 * 金额单位均为“分”，整除产生的不足 1 分余数计入未归属（划回企业），
 * 保证 {@code 归属额 + 未归属额 = 缴费额}。
 */
@Component
public class VestingCalculator {

    /** 基点制百分比：10000bp = 100%。 */
    public static final int BP_FULL = 10_000;

    /**
     * 计算入职日至申请日的整年司龄。
     *
     * @param hireDate        入职日（UTC）
     * @param applicationDate 支付申请受理日（UTC）
     * @return 整年数；申请日早于入职日时为 0
     */
    public int tenureYears(LocalDate hireDate, LocalDate applicationDate) {
        if (applicationDate.isBefore(hireDate)) {
            return 0;
        }
        long years = ChronoUnit.YEARS.between(hireDate, applicationDate);
        return Math.toIntExact(years);
    }

    /**
     * 按司龄返回企业缴费归属比例（基点）。
     *
     * @param tenureYears 整年司龄
     * @return 0 / 5000 / 8000 / 10000
     */
    public int employerVestingRateBp(int tenureYears) {
        if (tenureYears >= 8) {
            return BP_FULL;
        }
        if (tenureYears >= 5) {
            return 8_000;
        }
        if (tenureYears >= 3) {
            return 5_000;
        }
        return 0;
    }

    /**
     * 计算一笔企业缴费的归属金额（向下取整到分）。
     *
     * @param amountCents 缴费金额，单位：分
     * @param rateBp      归属比例，基点
     * @return 归属金额，单位：分
     */
    public long vestedAmount(long amountCents, int rateBp) {
        return Math.multiplyExact(amountCents, rateBp) / BP_FULL;
    }

    /**
     * 将计划总额拆分为各期金额，尾差（不足 1 期整分的余数）并入最后一期。
     *
     * @param totalAmountCents 计划总额，单位：分
     * @param count            期数，至少为 1
     * @return 各期金额，单位：分，求和恒等于计划总额
     */
    public List<Long> splitInstallments(long totalAmountCents, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("分期期数必须大于 0");
        }
        if (totalAmountCents < 0) {
            throw new IllegalArgumentException("计划总额不能为负");
        }
        List<Long> result = new ArrayList<>(count);
        long base = totalAmountCents / count;
        for (int i = 0; i < count - 1; i++) {
            result.add(base);
        }
        long distributed = Math.multiplyExact(base, (long) count - 1);
        result.add(totalAmountCents - distributed);
        return result;
    }
}
