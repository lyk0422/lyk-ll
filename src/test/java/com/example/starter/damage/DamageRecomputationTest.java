package com.example.starter.damage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.damage.api.Responsibility;
import com.example.starter.damage.api.dto.EstimateRequest;
import com.example.starter.damage.api.dto.RegisterRequest;
import com.example.starter.damage.api.dto.SurveyItemDto;
import com.example.starter.damage.api.dto.SurveyRequest;
import com.example.starter.damage.service.DamageCaseService;

/** 可复算：估价版本明细快照、残损清单与修理链可独立汇总并与版本头一致。 */
@SpringBootTest
class DamageRecomputationTest {

    private static final long D1 = LocalDate.of(2026, 9, 1)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

    @Autowired
    private DamageCaseService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        for (String table : List.of("case_event", "repair_cycle", "estimate_item",
                "estimate_version", "damage_item", "survey", "idempotent_request",
                "damage_case")) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
    }

    @Test
    @DisplayName("由 estimate_item 快照重算总额与最大单项，与估价版本头一致")
    void estimateVersionCanBeRecomputedFromSnapshot() {
        String caseNo = service.register(new RegisterRequest(
                "REQ-RC-REG", "CSQRC000001", new BigDecimal("2000.00"), D1)).caseNo();
        service.survey(new SurveyRequest("REQ-RC-SUR", caseNo, "检验员-孙", D1, List.of(
                new SurveyItemDto("IICL-4010", "角柱", Responsibility.OWNER, 1),
                new SurveyItemDto("IICL-3010", "门板", Responsibility.TERMINAL, 2),
                new SurveyItemDto("IICL-2010", "顶板", Responsibility.OWNER, 1))));
        var estimated = service.estimate(new EstimateRequest("REQ-RC-EST", caseNo));
        Long estimateId = estimated.currentEstimate().estimateId();

        Map<String, Object> head = jdbc.queryForMap(
                "SELECT total_amount, max_item_amount, subtotal_owner, subtotal_terminal, decision "
                        + "FROM estimate_version WHERE id = ?", estimateId);

        // 从版本明细快照 + 残损清单重算（不依赖版本头冗余字段）。
        BigDecimal recomputedTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(ei.amount), 0) FROM estimate_item ei WHERE ei.estimate_id = ?",
                BigDecimal.class, estimateId);
        BigDecimal recomputedMax = jdbc.queryForObject("""
                SELECT COALESCE(MAX(di.amount), 0)
                FROM estimate_item ei JOIN damage_item di ON di.id = ei.item_id
                WHERE ei.estimate_id = ?
                """, BigDecimal.class, estimateId);
        BigDecimal recomputedOwner = jdbc.queryForObject("""
                SELECT COALESCE(SUM(di.amount), 0)
                FROM estimate_item ei JOIN damage_item di ON di.id = ei.item_id
                WHERE ei.estimate_id = ? AND di.responsibility = 'OWNER'
                """, BigDecimal.class, estimateId);

        assertThat(recomputedTotal).isEqualByComparingTo((BigDecimal) head.get("total_amount"));
        assertThat(recomputedMax).isEqualByComparingTo((BigDecimal) head.get("max_item_amount"));
        assertThat(recomputedOwner).isEqualByComparingTo((BigDecimal) head.get("subtotal_owner"));
        // 300（箱东角柱）+300（码头门板150*2）+120（箱东顶板）=720，最大单项 300 ≤ 600 → 修理。
        assertThat(recomputedTotal).isEqualByComparingTo("720.00");
        assertThat(head.get("decision")).isEqualTo("REPAIR");
    }
}
