package com.example.starter.pension.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 司龄与企业缴费归属比例计算。
 *
 * <p>规则：司龄按入职日至支付申请日（UTC 日界）的整年数计算，恰满档位年限按该档归属；
 * 满 3 年 50%、满 5 年 80%、满 8 年 100%，不足 3 年 0%。个人缴费始终 100% 归属。</p>
 */
@Component
public class VestingCalculator {

    /** 50% 基点。 */
    public static final int RATIO_50_BP = 5000;
    /** 80% 基点。 */
    public static final int RATIO_80_BP = 8000;
    /** 100% 基点。 */
    public static final int RATIO_100_BP = 10000;

    /**
     * 计算完整司龄年数：入职日到申请日之间经过的整年数，不满一年不计。
     *
     * @param hireDate    入职日（UTC）
     * @param requestDate 支付申请日（UTC）
     * @return 完整年数，非负；申请日早于入职日时为 0
     */
    public int hireYears(LocalDate hireDate, LocalDate requestDate) {
        long years = ChronoUnit.YEARS.between(hireDate, requestDate);
        return (int) Math.max(0, years);
    }

    /**
     * 按司龄整年数返回企业缴费归属比例（基点）。
     *
     * @param hireYears 完整司龄年数
     * @return 0 / 5000 / 8000 / 10000
     */
    public int vestedRatioBp(int hireYears) {
        if (hireYears >= 8) {
            return RATIO_100_BP;
        }
        if (hireYears >= 5) {
            return RATIO_80_BP;
        }
        if (hireYears >= 3) {
            return RATIO_50_BP;
        }
        return 0;
    }

    /**
     * 计算企业缴费归属金额，精确到分；比例乘出的不足 1 分部分舍去（不计归属、不划回）。
     *
     * @param enterpriseAmountCents 企业缴费总额，单位：分
     * @param ratioBp               归属比例（基点）
     * @return 归属企业缴费金额，单位：分
     */
    public long vestedEnterprise(long enterpriseAmountCents, int ratioBp) {
        return Math.multiplyExact(enterpriseAmountCents, ratioBp) / 10000L;
    }
}
