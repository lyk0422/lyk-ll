package com.example.starter.damage.store;

import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.domain.Responsibility;
import com.example.starter.damage.dto.Views.ApprovalView;
import com.example.starter.damage.dto.Views.CompletionView;
import com.example.starter.damage.dto.Views.DamageItemView;
import com.example.starter.damage.dto.Views.EstimateView;
import com.example.starter.damage.dto.Views.EventView;
import com.example.starter.damage.dto.Views.ReinspectionView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 残损案子表数据访问：残损项、估价版本、修理批复、完工、复检与事件流。
 */
@Repository
public class DamageFlowRepository {

    private static final RowMapper<DamageItemView> ITEM_MAPPER = (rs, n) -> new DamageItemView(
            rs.getLong("id"),
            rs.getString("iicl_code"),
            rs.getString("component"),
            rs.getString("description"),
            Responsibility.valueOf(rs.getString("responsibility")),
            rs.getBigDecimal("repair_cost"));

    private static final RowMapper<EstimateView> ESTIMATE_MAPPER = (rs, n) -> new EstimateView(
            rs.getLong("id"),
            rs.getInt("version"),
            rs.getBigDecimal("total_cost"),
            Determination.valueOf(rs.getString("determination")),
            rs.getString("status"));

    private static final RowMapper<ApprovalView> APPROVAL_MAPPER = (rs, n) -> new ApprovalView(
            rs.getLong("id"),
            rs.getLong("estimate_id"),
            rs.getDate("approval_date").toLocalDate(),
            rs.getString("status"));

    private static final RowMapper<CompletionView> COMPLETION_MAPPER = (rs, n) -> new CompletionView(
            rs.getLong("id"),
            rs.getDate("completion_date").toLocalDate(),
            rs.getBoolean("rework"));

    private static final RowMapper<ReinspectionView> REINSPECTION_MAPPER = (rs, n) -> new ReinspectionView(
            rs.getLong("id"),
            rs.getBoolean("passed"));

    private static final RowMapper<EventView> EVENT_MAPPER = (rs, n) -> new EventView(
            rs.getLong("id"),
            rs.getString("event_type"),
            rs.getString("payload"));

    private final JdbcTemplate jdbc;

    public DamageFlowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 合成 IICL 代码是否存在 */
    public boolean iiclCodeExists(String code) {
        Integer count = jdbc.queryForObject("select count(*) from iicl_code where code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    /** 登记残损项 */
    public void insertItem(long caseId, String iiclCode, String component, String description,
                           Responsibility responsibility, BigDecimal repairCost) {
        jdbc.update("insert into damage_item(case_id, iicl_code, component, description, responsibility, repair_cost)"
                        + " values (?,?,?,?,?,?)",
                caseId, iiclCode, component, description, responsibility.name(), repairCost);
    }

    /** 残损清单（按登记顺序，可复算） */
    public List<DamageItemView> listItems(long caseId) {
        return jdbc.query("select * from damage_item where case_id = ? order by id", ITEM_MAPPER, caseId);
    }

    /** 新增估价版本，旧版本置 SUPERSEDED；返回新版本号 */
    public long insertEstimate(long caseId, BigDecimal totalCost, Determination determination,
                               String requestId, LocalDateTime now) {
        jdbc.update("update estimate set status = 'SUPERSEDED' where case_id = ? and status = 'CURRENT'", caseId);
        Integer max = jdbc.queryForObject("select coalesce(max(version), 0) from estimate where case_id = ?",
                Integer.class, caseId);
        int version = (max == null ? 0 : max) + 1;
        jdbc.update("insert into estimate(case_id, version, total_cost, determination, status, request_id, created_at)"
                        + " values (?,?,?,?,?,?,?)",
                caseId, version, totalCost, determination.name(), "CURRENT", requestId, now);
        Long id = jdbc.queryForObject("select max(id) from estimate where case_id = ?", Long.class, caseId);
        return id;
    }

    /** 估价版本列表（按版本号，可复算） */
    public List<EstimateView> listEstimates(long caseId) {
        return jdbc.query("select * from estimate where case_id = ? order by version", ESTIMATE_MAPPER, caseId);
    }

    /** 新增修理批复，旧批复置 SUPERSEDED；返回批复 ID */
    public long insertApproval(long caseId, long estimateId, LocalDate approvalDate, LocalDateTime now) {
        jdbc.update("update repair_approval set status = 'SUPERSEDED' where case_id = ? and status = 'ACTIVE'", caseId);
        jdbc.update("insert into repair_approval(case_id, estimate_id, approval_date, status, created_at)"
                        + " values (?,?,?,?,?)",
                caseId, estimateId, approvalDate, "ACTIVE", now);
        Long id = jdbc.queryForObject("select max(id) from repair_approval where case_id = ?", Long.class, caseId);
        return id;
    }

    /** 当前有效批复，无则 null */
    public ApprovalView findActiveApproval(long caseId) {
        return jdbc.query("select * from repair_approval where case_id = ? and status = 'ACTIVE' order by id desc",
                        APPROVAL_MAPPER, caseId)
                .stream().findFirst().orElse(null);
    }

    /** 批复列表 */
    public List<ApprovalView> listApprovals(long caseId) {
        return jdbc.query("select * from repair_approval where case_id = ? order by id", APPROVAL_MAPPER, caseId);
    }

    /** 登记修理完工 */
    public void insertCompletion(long caseId, LocalDate completionDate, boolean rework, LocalDateTime now) {
        jdbc.update("insert into repair_completion(case_id, completion_date, rework, created_at) values (?,?,?,?)",
                caseId, completionDate, rework, now);
    }

    /** 完工记录列表 */
    public List<CompletionView> listCompletions(long caseId) {
        return jdbc.query("select * from repair_completion where case_id = ? order by id", COMPLETION_MAPPER, caseId);
    }

    /** 登记复检结果 */
    public void insertReinspection(long caseId, boolean passed, LocalDateTime now) {
        jdbc.update("insert into reinspection(case_id, passed, created_at) values (?,?,?)",
                caseId, passed, now);
    }

    /** 复检记录列表 */
    public List<ReinspectionView> listReinspections(long caseId) {
        return jdbc.query("select * from reinspection where case_id = ? order by id", REINSPECTION_MAPPER, caseId);
    }

    /** 记录案件事件（如检验照片、结案后迟到照片） */
    public void insertEvent(long caseId, String eventType, String payload, LocalDateTime now) {
        jdbc.update("insert into case_event(case_id, event_type, payload, created_at) values (?,?,?,?)",
                caseId, eventType, payload, now);
    }

    /** 事件列表 */
    public List<EventView> listEvents(long caseId) {
        return jdbc.query("select * from case_event where case_id = ? order by id", EVENT_MAPPER, caseId);
    }

    /** 当前有效估价版本，无则 null */
    public EstimateView findCurrentEstimate(long caseId) {
        return jdbc.query("select * from estimate where case_id = ? and status = 'CURRENT' order by version desc",
                        ESTIMATE_MAPPER, caseId)
                .stream().findFirst().orElse(null);
    }

    /** 将当前有效批复置为 SUPERSEDED（重新估价或转全损时调用） */
    public void supersedeActiveApprovals(long caseId) {
        jdbc.update("update repair_approval set status = 'SUPERSEDED' where case_id = ? and status = 'ACTIVE'", caseId);
    }
}
