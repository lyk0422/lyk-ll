package com.example.starter.damage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.api.CaseStatus;
import com.example.starter.damage.api.Responsibility;
import com.example.starter.damage.api.dto.RegisterRequest;
import com.example.starter.damage.api.dto.SurveyItemDto;
import com.example.starter.damage.api.dto.SurveyRequest;
import com.example.starter.damage.service.DamageCaseService;

/** 事务边界：失败请求回滚幂等占位与全部副作用，同键纠正后可成功，无脏数据。 */
@SpringBootTest
class DamageTransactionBoundaryTest {

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
    @DisplayName("检验因未知代码400回滚：幂等占位与残损数据不落库；同键纠正参数后成功")
    void failedRequestRollsBackIdempotencyPlaceholder() {
        String caseNo = service.register(new RegisterRequest(
                "REQ-TB-REG", "CSQTB000001", new BigDecimal("1000.00"), D1)).caseNo();

        assertThatThrownBy(() -> service.survey(new SurveyRequest(
                "REQ-TB-SUR", caseNo, "检验员-赵", D1,
                List.of(new SurveyItemDto("IICL-9999", "错误位置", Responsibility.OWNER, 1)))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("UNKNOWN_IICL_CODE");

        Integer placeholder = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'REQ-TB-SUR'",
                Integer.class);
        assertThat(placeholder).isZero();
        Integer surveys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM survey WHERE case_id = (SELECT id FROM damage_case WHERE case_no = ?)",
                Integer.class, caseNo);
        assertThat(surveys).isZero();
        Integer items = jdbc.queryForObject("SELECT COUNT(*) FROM damage_item", Integer.class);
        assertThat(items).isZero();

        // 同一 requestId 用合法参数重试：不是 409，正常检验成功。
        var retried = service.survey(new SurveyRequest(
                "REQ-TB-SUR", caseNo, "检验员-赵", D1,
                List.of(new SurveyItemDto("IICL-4010", "左前角柱", Responsibility.OWNER, 1))));
        assertThat(retried.status()).isEqualTo(CaseStatus.SURVEYED.name());
        Integer surveysAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM survey WHERE case_id = (SELECT id FROM damage_case WHERE case_no = ?)",
                Integer.class, caseNo);
        assertThat(surveysAfter).isEqualTo(1);
    }

    @Test
    @DisplayName("逾期完工抛422时主事务回滚，但逾期事实在独立事务保留以支持重新估价")
    void expiryFactPersistsIndependentlyWhileMainTxRollsBack() {
        String caseNo = service.register(new RegisterRequest(
                "REQ-TB-REG2", "CSQTB000002", new BigDecimal("1000.00"), D1)).caseNo();
        service.survey(new SurveyRequest("REQ-TB-SUR2", caseNo, "检验员-赵", D1,
                List.of(new SurveyItemDto("IICL-4010", "角柱", Responsibility.OWNER, 1))));
        service.estimate(new com.example.starter.damage.api.dto.EstimateRequest("REQ-TB-EST2", caseNo));
        service.approveRepair(new com.example.starter.damage.api.dto.ApproveRepairRequest(
                "REQ-TB-APV2", caseNo, D1 + 3_600_000L));

        long day8 = D1 + 8L * 86_400_000L;
        assertThatThrownBy(() -> service.completeRepair(
                new com.example.starter.damage.api.dto.CompleteRepairRequest(
                        "REQ-TB-CMP2", caseNo, day8)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("APPROVAL_EXPIRED");

        // 逾期 422 的幂等占位随主事务回滚（同键重报不会误重放），EXPIRED 事实保留。
        Integer placeholder = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'REQ-TB-CMP2'",
                Integer.class);
        assertThat(placeholder).isZero();
        String cycleStatus = jdbc.queryForObject(
                "SELECT rc.status FROM repair_cycle rc JOIN damage_case dc ON dc.id = rc.case_id "
                        + "WHERE dc.case_no = ?", String.class, caseNo);
        assertThat(cycleStatus).isEqualTo("EXPIRED");
        Integer expireEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM case_event WHERE event_type = 'REPAIR_EXPIRED' "
                        + "AND case_id = (SELECT id FROM damage_case WHERE case_no = ?)",
                Integer.class, caseNo);
        assertThat(expireEvents).isEqualTo(1);
    }
}
