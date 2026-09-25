package com.example.starter.water.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 取水户与年度额度台账仓储。
 */
public interface WaterUserRepository {

    /**
     * 取水户行；quota 为年度许可总量快照，单位立方米（m³）。
     */
    record UserRow(
            Long id,
            String name,
            String riverSection,
            BigDecimal annualQuota,
            Instant createdAt) {
    }

    /**
     * 年度额度行。
     *
     * @param carryoverDeduction 上一超许可年度按 1:1 结转的扣减额度（m³），无结转为 0
     * @param sealedActualTotal  封存时冻结的当年实际取水总量（m³）；未封存为 null
     * @param excess             封存结算出的超许可部分（m³）；未封存为 null
     */
    record AnnualQuotaRow(
            Long id,
            Long userId,
            int year,
            BigDecimal permittedQuota,
            BigDecimal carryoverDeduction,
            boolean sealed,
            Instant sealedAt,
            BigDecimal sealedActualTotal,
            BigDecimal excess) {
    }

    UserRow create(String name, String riverSection, BigDecimal annualQuota, Instant now);

    Optional<UserRow> findById(Long id);

    /**
     * 行级锁定取水户（SELECT ... FOR UPDATE），用于申报/封存/限取触发之间的串行化。
     */
    UserRow lockById(Long id);

    /**
     * 行级锁定指定河段全部取水户并按 ID 排序返回，限取触发与申报并发时由此形成唯一先后顺序。
     */
    List<UserRow> lockByRiverSection(String riverSection);

    /**
     * 读取（或惰性创建）指定年度额度行：permitted 取取水户许可总量快照，carryover 取上年超额。
     */
    AnnualQuotaRow getOrCreateAnnualQuota(Long userId, int year, BigDecimal permittedQuota);

    /**
     * 只读查询指定年度额度行；未产生任何申报/封存时为空，台账据此呈现零额度视图。
     */
    Optional<AnnualQuotaRow> findAnnualQuota(Long userId, int year);

    /**
     * 持久化封存结果，并把超额按 1:1 写入次年 carryover_deduction；次年已封存（终态）时不改写。
     */
    void applySeal(AnnualQuotaRow row, Instant sealedAt, BigDecimal actualTotal,
                   BigDecimal excess, int nextYear);
}
