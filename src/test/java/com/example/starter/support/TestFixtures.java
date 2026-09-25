package com.example.starter.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 测试夹具：每个用例前清空全部业务表并重建合成基础数据（鱼种、渔船），
 * 配额台账按用例需要单独设置，避免用例间顺序依赖。
 */
@Component
public class TestFixtures {

    private final JdbcTemplate jdbc;

    public TestFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 清空业务数据并重建鱼种与渔船（合成数据）。 */
    public void reset() {
        for (String table : List.of("idempotency_record", "late_log_event", "review_case",
                "settlement_item", "settlement", "arrival_item", "arrival_report",
                "logbook_item", "logbook_entry", "declaration_item", "departure_declaration",
                "voyage", "quota_ledger", "vessel", "species")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO species (code, name, protected_flag) VALUES ('COD', '鳕鱼', FALSE)");
        jdbc.update("INSERT INTO species (code, name, protected_flag) VALUES ('HAR', '带鱼', FALSE)");
        jdbc.update("INSERT INTO species (code, name, protected_flag) VALUES ('PSH', '保护鲨', TRUE)");
        jdbc.update("INSERT INTO vessel (id, vessel_no, name) VALUES (1, 'V001', '远洋一号')");
        jdbc.update("INSERT INTO vessel (id, vessel_no, name) VALUES (2, 'V002', '远洋二号')");
    }

    /** 设置渔船某鱼种某年度（UTC）配额总量（kg）。 */
    public void setQuota(long vesselId, String species, int year, String totalKg) {
        jdbc.update("INSERT INTO quota_ledger (vessel_id, species_code, quota_year, total_quota,"
                        + " used_quota, frozen) VALUES (?, ?, ?, ?, 0, FALSE)",
                vesselId, species, year, new BigDecimal(totalKg));
    }

    /** 调整台账配额总量（模拟配额核定变更）。 */
    public void updateTotalQuota(long vesselId, String species, int year, String totalKg) {
        jdbc.update("UPDATE quota_ledger SET total_quota = ?"
                        + " WHERE vessel_id = ? AND species_code = ? AND quota_year = ?",
                new BigDecimal(totalKg), vesselId, species, year);
    }

    /** 台账已核销量（kg）。 */
    public BigDecimal usedQuota(long vesselId, String species, int year) {
        return jdbc.queryForObject("SELECT used_quota FROM quota_ledger"
                        + " WHERE vessel_id = ? AND species_code = ? AND quota_year = ?",
                BigDecimal.class, vesselId, species, year);
    }

    /** 台账冻结标记。 */
    public boolean ledgerFrozen(long vesselId, String species, int year) {
        Boolean frozen = jdbc.queryForObject("SELECT frozen FROM quota_ledger"
                        + " WHERE vessel_id = ? AND species_code = ? AND quota_year = ?",
                Boolean.class, vesselId, species, year);
        return Boolean.TRUE.equals(frozen);
    }

    /** 航次指定类型的待复核案件数。 */
    public int openReviews(long voyageId, String reviewType) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM review_case"
                        + " WHERE voyage_id = ? AND review_type = ? AND status = 'OPEN'",
                Integer.class, voyageId, reviewType);
        return count == null ? 0 : count;
    }

    /** 航次迟到日志事件数。 */
    public int lateEvents(long voyageId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM late_log_event WHERE voyage_id = ?",
                Integer.class, voyageId);
        return count == null ? 0 : count;
    }

    /** 航次数（按渔船）。 */
    public int voyageCount(long vesselId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM voyage WHERE vessel_id = ?",
                Integer.class, vesselId);
        return count == null ? 0 : count;
    }

    /** 航次核销单数。 */
    public int settlementCount(long voyageId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM settlement WHERE voyage_id = ?",
                Integer.class, voyageId);
        return count == null ? 0 : count;
    }

    /** 航次状态。 */
    public String voyageStatus(long voyageId) {
        return jdbc.queryForObject("SELECT status FROM voyage WHERE id = ?", String.class, voyageId);
    }

    /** 日志条数（按去重键）。 */
    public int logCount(long vesselId, String logDate, String fishingArea) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM logbook_entry"
                        + " WHERE vessel_id = ? AND log_date = ? AND fishing_area = ?",
                Integer.class, vesselId, logDate, fishingArea);
        return count == null ? 0 : count;
    }
}
