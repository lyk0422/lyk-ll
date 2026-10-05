package com.example.starter.museum;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 借展合同服务：申请→点交→在展→归还点交→结案的状态流转，
 * 含一级文物唯一有效合同、3 年期限（UTC 日界）、续展窗口与双方点交确认。
 */
@Service
public class LoanService {

    /** 借展期限上限：3 年（恰满 3 年合法）。 */
    static final int MAX_LOAN_YEARS = 3;
    /** 续展受理窗口：届满前 60 个自然日起（恰第 60 日受理）。 */
    static final int EXTEND_WINDOW_DAYS = 60;

    /** 合同状态：已申请（待交付点交）。 */
    public static final String STATUS_APPLIED = "APPLIED";
    /** 合同状态：在展。 */
    public static final String STATUS_ON_DISPLAY = "ON_DISPLAY";
    /** 合同状态：环境异常挂起。 */
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    /** 合同状态：归还点交完成（待结案）。 */
    public static final String STATUS_RETURNED = "RETURNED";
    /** 合同状态：已结案（终态）。 */
    public static final String STATUS_CLOSED = "CLOSED";

    /** 点交参与方：馆方。 */
    public static final String PARTY_MUSEUM = "MUSEUM";
    /** 点交参与方：借展方。 */
    public static final String PARTY_BORROWER = "BORROWER";

    /** 借展合同行记录。 */
    public record Contract(long id, long artifactId, long showcaseId, String borrower,
            LocalDate startDate, LocalDate endDate, LocalDate originalEndDate,
            boolean extended, String status, String suspendReason, long version) {
    }

    private static final RowMapper<Contract> CONTRACT_MAPPER = new RowMapper<>() {
        @Override
        public Contract mapRow(ResultSet rs, int rowNum) throws SQLException {
            String reason = rs.getString("suspend_reason");
            return new Contract(
                    rs.getLong("id"),
                    rs.getLong("artifact_id"),
                    rs.getLong("showcase_id"),
                    rs.getString("borrower"),
                    rs.getDate("start_date").toLocalDate(),
                    rs.getDate("end_date").toLocalDate(),
                    rs.getDate("original_end_date").toLocalDate(),
                    rs.getBoolean("extended"),
                    rs.getString("status"),
                    reason,
                    rs.getLong("version"));
        }
    };

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ImageHandoverService imageHandoverService;

    public LoanService(JdbcTemplate jdbc, Clock clock, ImageHandoverService imageHandoverService) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.imageHandoverService = imageHandoverService;
    }

    /**
     * 受理借展申请：校验期限（最长 3 年，恰满合法）与展柜状态，
     * 通过 artifact_active_loan 主键保证同一文物同一时刻仅一个有效合同。
     */
    @Transactional
    public Map<String, Object> apply(String requestId, long artifactId, long showcaseId,
            String borrower, LocalDate startDate, LocalDate endDate) {
        if (borrower == null || borrower.isBlank()) {
            throw ApiException.badRequest("借展方 borrower 不能为空");
        }
        if (startDate == null || endDate == null) {
            throw ApiException.badRequest("借展起止日期不能为空");
        }
        if (endDate.isBefore(startDate)) {
            throw ApiException.unprocessable("借展截止日早于开始日");
        }
        if (endDate.isAfter(startDate.plusYears(MAX_LOAN_YEARS))) {
            throw ApiException.unprocessable("借展期限超过 3 年上限");
        }
        Map<String, Object> artifact = findById("artifact", artifactId);
        Map<String, Object> showcase = findById("showcase", showcaseId);
        if (Boolean.TRUE.equals(showcase.get("frozen"))) {
            throw ApiException.conflict("展柜已冻结新入藏，暂不受理借展");
        }
        Instant now = Instant.now(clock);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO loan_contract (request_id, artifact_id, showcase_id, borrower,"
                            + " start_date, end_date, original_end_date, extended, status, version, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,FALSE,?,0,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setLong(2, artifactId);
            ps.setLong(3, showcaseId);
            ps.setString(4, borrower);
            ps.setDate(5, Date.valueOf(startDate));
            ps.setDate(6, Date.valueOf(endDate));
            ps.setDate(7, Date.valueOf(endDate));
            ps.setString(8, STATUS_APPLIED);
            ps.setTimestamp(9, Timestamp.from(now));
            ps.setTimestamp(10, Timestamp.from(now));
            return ps;
        }, keyHolder);
        long contractId = keyHolder.getKey().longValue();
        try {
            jdbc.update("INSERT INTO artifact_active_loan (artifact_id, contract_id) VALUES (?,?)",
                    artifactId, contractId);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("同一文物已存在有效借展合同，本次申请不予受理");
        }
        Map<String, Object> view = contractView(getContract(contractId));
        view.put("artifactLevel", artifact.get("artifact_level"));
        return view;
    }

    /**
     * 点交确认：交付或归还点交均须馆方与借展方在同一点交单上确认，单方确认不生效。
     *
     * @param handoverType DELIVERY 交付点交 / RETURN 归还点交
     * @param party        MUSEUM 馆方 / BORROWER 借展方
     */
    @Transactional
    public Map<String, Object> confirmHandover(long contractId, String handoverType, String party) {
        return confirmHandover(contractId, handoverType, party, null);
    }

    /**
     * 点交确认（携带幂等请求号）：归还点交双方确认生效时，在同一事务内自动比对
     * 出借与归还合成影像特征清单，差异项超过 0 生成差异工单；恰一致则放行。
     *
     * @param requestId 触发本次确认的幂等请求号，服务内直接调用时为 null
     */
    @Transactional
    public Map<String, Object> confirmHandover(long contractId, String handoverType, String party,
            String requestId) {
        Contract contract = getContract(contractId);
        String expectedStatus;
        String targetStatus;
        if ("DELIVERY".equals(handoverType)) {
            expectedStatus = STATUS_APPLIED;
            targetStatus = STATUS_ON_DISPLAY;
        } else if ("RETURN".equals(handoverType)) {
            expectedStatus = STATUS_ON_DISPLAY;
            targetStatus = STATUS_RETURNED;
        } else {
            throw ApiException.badRequest("未知点交类型：" + handoverType);
        }
        validateParty(party);
        if (!expectedStatus.equals(contract.status())) {
            throw ApiException.conflict(
                    "合同状态为 " + contract.status() + "，不接受 " + handoverType + " 点交确认");
        }
        long handoverId = ensureHandover(contractId, handoverType);
        String column = PARTY_MUSEUM.equals(party) ? "museum_confirmed" : "borrower_confirmed";
        jdbc.update("UPDATE handover SET " + column + " = TRUE WHERE id = ?", handoverId);
        Map<String, Object> handover = jdbc.queryForMap(
                "SELECT museum_confirmed, borrower_confirmed, completed_at FROM handover WHERE id = ?", handoverId);
        boolean bothConfirmed = Boolean.TRUE.equals(handover.get("museum_confirmed"))
                && Boolean.TRUE.equals(handover.get("borrower_confirmed"));
        boolean completed = handover.get("completed_at") != null;
        Map<String, Object> imageComparison = null;
        if (bothConfirmed && !completed) {
            Instant now = Instant.now(clock);
            int updated = jdbc.update(
                    "UPDATE loan_contract SET status = ?, version = version + 1, updated_at = ?"
                            + " WHERE id = ? AND status = ? AND version = ?",
                    targetStatus, Timestamp.from(now), contractId, expectedStatus, contract.version());
            if (updated == 1) {
                jdbc.update("UPDATE handover SET completed_at = ? WHERE id = ?", Timestamp.from(now), handoverId);
                completed = true;
            } else {
                Contract current = getContract(contractId);
                if (targetStatus.equals(current.status())) {
                    completed = true;
                } else {
                    throw ApiException.conflict("合同状态已并发变更，点交未生效（与续展等流程互斥）");
                }
            }
            if (completed && "RETURN".equals(handoverType)) {
                // 归还点交生效：同事务自动比对合成影像特征，差异项超过 0 生成差异工单
                imageComparison = imageHandoverService.compareOnReturn(contractId, requestId);
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("contractId", contractId);
        view.put("handoverId", handoverId);
        view.put("handoverType", handoverType);
        view.put("museumConfirmed", handover.get("museum_confirmed"));
        view.put("borrowerConfirmed", handover.get("borrower_confirmed"));
        view.put("effective", bothConfirmed && completed);
        if (imageComparison != null) {
            view.put("imageComparison", imageComparison);
        }
        view.put("contractStatus", getContract(contractId).status());
        return view;
    }

    /**
     * 续展审批：届满前 60 个自然日起受理（恰第 60 日合法，更早拒绝），仅可续展一次，
     * 续展时长不超过原期限一半（恰一半合法）。与归还点交并发时由条件更新保证唯一终态。
     */
    @Transactional
    public Map<String, Object> extend(long contractId, LocalDate newEndDate) {
        if (newEndDate == null) {
            throw ApiException.badRequest("续展截止日不能为空");
        }
        Contract contract = getContract(contractId);
        if (!STATUS_APPLIED.equals(contract.status()) && !STATUS_ON_DISPLAY.equals(contract.status())) {
            throw ApiException.conflict("合同状态为 " + contract.status() + "，不可续展");
        }
        if (contract.extended()) {
            throw ApiException.conflict("该合同已续展过一次，不可再次续展");
        }
        LocalDate today = LocalDate.now(clock);
        if (today.isBefore(contract.endDate().minusDays(EXTEND_WINDOW_DAYS))) {
            throw ApiException.unprocessable("未到续展受理窗口（届满前 60 个自然日起受理）");
        }
        if (!newEndDate.isAfter(contract.endDate())) {
            throw ApiException.unprocessable("续展截止日须晚于当前截止日");
        }
        long originalDays = ChronoUnit.DAYS.between(contract.startDate(), contract.originalEndDate());
        long extensionDays = ChronoUnit.DAYS.between(contract.endDate(), newEndDate);
        if (extensionDays > originalDays / 2) {
            throw ApiException.unprocessable("续展时长超过原期限一半");
        }
        Instant now = Instant.now(clock);
        int updated = jdbc.update(
                "UPDATE loan_contract SET end_date = ?, extended = TRUE, version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND status IN (?, ?) AND extended = FALSE AND version = ?",
                Date.valueOf(newEndDate), Timestamp.from(now), contractId, STATUS_APPLIED, STATUS_ON_DISPLAY,
                contract.version());
        if (updated == 0) {
            throw ApiException.conflict("合同状态已变更，续展未生效（与归还点交流程互斥）");
        }
        return contractView(getContract(contractId));
    }

    /**
     * 结案：仅归还点交完成后可结案；结案为终态，重复结案返回幂等成功。
     */
    @Transactional
    public Map<String, Object> close(long contractId) {
        Contract contract = getContract(contractId);
        if (STATUS_CLOSED.equals(contract.status())) {
            Map<String, Object> view = contractView(contract);
            view.put("idempotentReplay", true);
            return view;
        }
        if (!STATUS_RETURNED.equals(contract.status())) {
            throw ApiException.conflict("合同状态为 " + contract.status() + "，须完成归还点交后方可结案");
        }
        Instant now = Instant.now(clock);
        // 结案校验与差异工单状态原子判定：仅当不存在进行中差异工单时条件更新才能命中一行
        int updated = jdbc.update(
                "UPDATE loan_contract SET status = ?, version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND status = ? AND NOT EXISTS ("
                        + " SELECT 1 FROM discrepancy_order o WHERE o.contract_id = loan_contract.id"
                        + " AND o.status = 'OPEN')",
                STATUS_CLOSED, Timestamp.from(now), contractId, STATUS_RETURNED);
        if (updated == 0) {
            Contract current = getContract(contractId);
            if (STATUS_CLOSED.equals(current.status())) {
                Map<String, Object> view = contractView(current);
                view.put("idempotentReplay", true);
                return view;
            }
            if (imageHandoverService.countOpenOrders(contractId) > 0) {
                throw ApiException.conflict("存在未闭环的影像差异工单，须先闭环后方可结案");
            }
            throw ApiException.conflict("合同状态已变更，结案未生效");
        }
        jdbc.update("DELETE FROM artifact_active_loan WHERE artifact_id = ?", contract.artifactId());
        return contractView(getContract(contractId));
    }

    /**
     * 环境异常挂起：由监测服务触发，将在展合同置为挂起。
     *
     * @return true 表示本次调用触发了状态变更
     */
    @Transactional
    public boolean suspend(long contractId, String reason) {
        int updated = jdbc.update(
                "UPDATE loan_contract SET status = ?, suspend_reason = ?, version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND status = ?",
                STATUS_SUSPENDED, reason, Timestamp.from(Instant.now(clock)), contractId, STATUS_ON_DISPLAY);
        return updated == 1;
    }

    /**
     * 挂起恢复复核：须馆方与借展方双方复核，且展柜无未处置保护工单，方可恢复在展。
     */
    @Transactional
    public Map<String, Object> resume(long contractId, String party) {
        validateParty(party);
        Contract contract = getContract(contractId);
        if (!STATUS_SUSPENDED.equals(contract.status())) {
            throw ApiException.conflict("合同状态为 " + contract.status() + "，不在挂起中，无需恢复复核");
        }
        Integer pendingOrders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM protection_order WHERE showcase_id = ? AND status IN ('OPEN','ESCALATED')",
                Integer.class, contract.showcaseId());
        if (pendingOrders != null && pendingOrders > 0) {
            throw ApiException.conflict("展柜存在未处置的保护工单，须先完成处置确认");
        }
        try {
            jdbc.update("INSERT INTO resume_review (contract_id, party, reviewed_at) VALUES (?,?,?)",
                    contractId, party, Timestamp.from(Instant.now(clock)));
        } catch (DuplicateKeyException e) {
            // 同一方重复复核：幂等忽略
        }
        List<String> parties = jdbc.queryForList(
                "SELECT party FROM resume_review WHERE contract_id = ?", String.class, contractId);
        boolean bothReviewed = parties.contains(PARTY_MUSEUM) && parties.contains(PARTY_BORROWER);
        if (bothReviewed) {
            jdbc.update(
                    "UPDATE loan_contract SET status = ?, suspend_reason = NULL, version = version + 1, updated_at = ?"
                            + " WHERE id = ? AND status = ?",
                    STATUS_ON_DISPLAY, Timestamp.from(Instant.now(clock)), contractId, STATUS_SUSPENDED);
            jdbc.update("DELETE FROM resume_review WHERE contract_id = ?", contractId);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("contractId", contractId);
        view.put("reviewedParties", parties);
        view.put("resumed", bothReviewed);
        view.put("contractStatus", getContract(contractId).status());
        return view;
    }

    /**
     * 查询合同详情。
     */
    public Map<String, Object> get(long contractId) {
        return contractView(getContract(contractId));
    }

    /**
     * 查询文物借展历史（含已结案），供复算。
     */
    public List<Map<String, Object>> history(long artifactId) {
        return jdbc.query("SELECT * FROM loan_contract WHERE artifact_id = ? ORDER BY id",
                CONTRACT_MAPPER, artifactId).stream().map(this::contractView).toList();
    }

    Contract getContract(long contractId) {
        try {
            return jdbc.queryForObject("SELECT * FROM loan_contract WHERE id = ?", CONTRACT_MAPPER, contractId);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.notFound("合同不存在：" + contractId);
        }
    }

    /** 查询展柜上当前有效（在展/挂起）合同，供监测服务关联。 */
    Contract findActiveContractByShowcase(long showcaseId) {
        List<Contract> contracts = jdbc.query(
                "SELECT * FROM loan_contract WHERE showcase_id = ? AND status IN (?, ?) ORDER BY id",
                CONTRACT_MAPPER, showcaseId, STATUS_ON_DISPLAY, STATUS_SUSPENDED);
        return contracts.isEmpty() ? null : contracts.get(0);
    }

    /** 查询展柜最近一份已结案合同，供迟到读数判定。 */
    Contract findLatestClosedContractByShowcase(long showcaseId) {
        List<Contract> contracts = jdbc.query(
                "SELECT * FROM loan_contract WHERE showcase_id = ? AND status = ? ORDER BY id DESC LIMIT 1",
                CONTRACT_MAPPER, showcaseId, STATUS_CLOSED);
        return contracts.isEmpty() ? null : contracts.get(0);
    }

    private long ensureHandover(long contractId, String handoverType) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM handover WHERE contract_id = ? AND handover_type = ?",
                Long.class, contractId, handoverType);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var ps = connection.prepareStatement(
                        "INSERT INTO handover (contract_id, handover_type, created_at) VALUES (?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, contractId);
                ps.setString(2, handoverType);
                ps.setTimestamp(3, Timestamp.from(Instant.now(clock)));
                return ps;
            }, keyHolder);
            return keyHolder.getKey().longValue();
        } catch (DuplicateKeyException e) {
            return jdbc.queryForObject(
                    "SELECT id FROM handover WHERE contract_id = ? AND handover_type = ?",
                    Long.class, contractId, handoverType);
        }
    }

    private Map<String, Object> findById(String table, long id) {
        try {
            return jdbc.queryForMap("SELECT * FROM " + table + " WHERE id = ?", id);
        } catch (EmptyResultDataAccessException e) {
            throw ApiException.notFound(table + " 不存在：" + id);
        }
    }

    private void validateParty(String party) {
        if (!PARTY_MUSEUM.equals(party) && !PARTY_BORROWER.equals(party)) {
            throw ApiException.badRequest("确认方须为 MUSEUM 或 BORROWER");
        }
    }

    private Map<String, Object> contractView(Contract c) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("contractId", c.id());
        view.put("artifactId", c.artifactId());
        view.put("showcaseId", c.showcaseId());
        view.put("borrower", c.borrower());
        view.put("startDate", c.startDate().toString());
        view.put("endDate", c.endDate().toString());
        view.put("originalEndDate", c.originalEndDate().toString());
        view.put("extended", c.extended());
        view.put("status", c.status());
        view.put("suspendReason", c.suspendReason());
        view.put("version", c.version());
        return view;
    }
}
