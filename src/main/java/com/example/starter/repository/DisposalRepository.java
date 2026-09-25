package com.example.starter.repository;

import com.example.starter.domain.DisposalChain;
import com.example.starter.domain.EvacuationArrival;
import com.example.starter.domain.ResumeAssessmentRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 处置链仓储：处置链（执行中槽位由 active_key 唯一索引保护）、撤人到位记录、复工评估。
 */
@Repository
public class DisposalRepository {

    private static final RowMapper<DisposalChain> CHAIN_MAPPER = (rs, rowNum) -> {
        DisposalChain c = new DisposalChain();
        c.setId(rs.getLong("id"));
        c.setChainNo(rs.getString("chain_no"));
        c.setZoneCode(rs.getString("zone_code"));
        c.setRequestId(rs.getString("request_id"));
        c.setAlarmLevel(rs.getString("alarm_level"));
        Timestamp triggerTime = rs.getTimestamp("trigger_time");
        c.setTriggerTime(triggerTime == null ? null : triggerTime.toLocalDateTime());
        c.setStatus(rs.getString("status"));
        c.setActiveKey(rs.getString("active_key"));
        c.setEvacStatus(rs.getString("evac_status"));
        Timestamp evacConfirmTime = rs.getTimestamp("evac_confirm_time");
        c.setEvacConfirmTime(evacConfirmTime == null ? null : evacConfirmTime.toLocalDateTime());
        c.setResumeStatus(rs.getString("resume_status"));
        Timestamp resumeApplyTime = rs.getTimestamp("resume_apply_time");
        c.setResumeApplyTime(resumeApplyTime == null ? null : resumeApplyTime.toLocalDateTime());
        Timestamp resumeDecideTime = rs.getTimestamp("resume_decide_time");
        c.setResumeDecideTime(resumeDecideTime == null ? null : resumeDecideTime.toLocalDateTime());
        Timestamp archiveTime = rs.getTimestamp("archive_time");
        c.setArchiveTime(archiveTime == null ? null : archiveTime.toLocalDateTime());
        Timestamp createdAt = rs.getTimestamp("created_at");
        c.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
        return c;
    };

    private static final RowMapper<EvacuationArrival> ARRIVAL_MAPPER = (rs, rowNum) -> {
        EvacuationArrival a = new EvacuationArrival();
        a.setId(rs.getLong("id"));
        a.setChainId(rs.getLong("chain_id"));
        a.setZoneCode(rs.getString("zone_code"));
        a.setPersonId(rs.getString("person_id"));
        Timestamp arrivedAt = rs.getTimestamp("arrived_at");
        a.setArrivedAt(arrivedAt == null ? null : arrivedAt.toLocalDateTime());
        return a;
    };

    private static final RowMapper<ResumeAssessmentRecord> RESUME_MAPPER = (rs, rowNum) -> {
        ResumeAssessmentRecord r = new ResumeAssessmentRecord();
        r.setId(rs.getLong("id"));
        r.setChainId(rs.getLong("chain_id"));
        r.setApplyRequestId(rs.getString("apply_request_id"));
        Timestamp applyTime = rs.getTimestamp("apply_time");
        r.setApplyTime(applyTime == null ? null : applyTime.toLocalDateTime());
        Timestamp safeSince = rs.getTimestamp("safe_since");
        r.setSafeSince(safeSince == null ? null : safeSince.toLocalDateTime());
        r.setStatus(rs.getString("status"));
        int site = rs.getInt("site_confirmed");
        r.setSiteConfirmed(rs.wasNull() ? null : site == 1);
        int monitor = rs.getInt("monitor_confirmed");
        r.setMonitorConfirmed(rs.wasNull() ? null : monitor == 1);
        r.setDecideRequestId(rs.getString("decide_request_id"));
        Timestamp decideTime = rs.getTimestamp("decide_time");
        r.setDecideTime(decideTime == null ? null : decideTime.toLocalDateTime());
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        r.setDecidedAt(decidedAt == null ? null : decidedAt.toLocalDateTime());
        r.setRemark(rs.getString("remark"));
        return r;
    };

    private final JdbcTemplate jdbcTemplate;

    public DisposalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新建处置链并占用测区执行中槽位；槽位冲突由 active_key 唯一索引拒绝，自增主键回填。
     */
    public void insertChain(DisposalChain chain) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO disposal_chain (chain_no, zone_code, request_id, alarm_level, trigger_time, "
                            + "status, active_key, evac_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, chain.getChainNo());
            ps.setString(2, chain.getZoneCode());
            ps.setString(3, chain.getRequestId());
            ps.setString(4, chain.getAlarmLevel());
            ps.setTimestamp(5, Timestamp.valueOf(chain.getTriggerTime()));
            ps.setString(6, chain.getStatus());
            ps.setString(7, chain.getActiveKey());
            ps.setString(8, chain.getEvacStatus());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            chain.setId(key.longValue());
        }
    }

    public DisposalChain findChainByNo(String chainNo) {
        List<DisposalChain> list = jdbcTemplate.query(chainSelect() + " WHERE chain_no = ?", CHAIN_MAPPER, chainNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public DisposalChain findActiveChainByZone(String zoneCode) {
        List<DisposalChain> list = jdbcTemplate.query(
                chainSelect() + " WHERE active_key = ?", CHAIN_MAPPER, zoneCode);
        return list.isEmpty() ? null : list.get(0);
    }

    public DisposalChain findChainByRequestId(String requestId) {
        List<DisposalChain> list = jdbcTemplate.query(
                chainSelect() + " WHERE request_id = ?", CHAIN_MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 是否存在已归档处置链（用于判定上报数据是否为归档后迟到数据）。
     */
    public boolean existsArchivedChain(String zoneCode) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disposal_chain WHERE zone_code = ? AND status = 'ARCHIVED'",
                Integer.class, zoneCode);
        return c != null && c > 0;
    }

    /**
     * 查最近一条已归档处置链（归档时刻最晚），用于迟到数据判定。
     */
    public DisposalChain findLatestArchivedChain(String zoneCode) {
        List<DisposalChain> list = jdbcTemplate.query(
                chainSelect() + " WHERE zone_code = ? AND status = 'ARCHIVED' "
                        + "ORDER BY archive_time DESC LIMIT 1", CHAIN_MAPPER, zoneCode);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 红色预警时生成撤人快照（在册人员一人一行，arrived_at 为空）。
     */
    public void insertEvacSnapshot(long chainId, String zoneCode, List<String> personIds) {
        for (String personId : personIds) {
            jdbcTemplate.update(
                    "INSERT INTO evacuation_person (chain_id, zone_code, person_id, arrived_at) VALUES (?, ?, ?, NULL)",
                    chainId, zoneCode, personId);
        }
    }

    /**
     * 单人到位：重复上报取最早到位时刻（不覆盖更早值）。
     *
     * @return 快照内是否存在该人员
     */
    public boolean reportArrival(long chainId, String personId, LocalDateTime arrivedAt) {
        int exists = jdbcTemplate.update(
                "UPDATE evacuation_person SET arrived_at = CASE WHEN arrived_at IS NULL OR arrived_at > ? "
                        + "THEN ? ELSE arrived_at END WHERE chain_id = ? AND person_id = ?",
                Timestamp.valueOf(arrivedAt), Timestamp.valueOf(arrivedAt), chainId, personId);
        return exists > 0;
    }

    public int countEvacPeople(long chainId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evacuation_person WHERE chain_id = ?", Integer.class, chainId);
        return c == null ? 0 : c;
    }

    public int countArrived(long chainId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evacuation_person WHERE chain_id = ? AND arrived_at IS NOT NULL",
                Integer.class, chainId);
        return c == null ? 0 : c;
    }

    public List<EvacuationArrival> findArrivals(long chainId) {
        return jdbcTemplate.query(
                "SELECT id, chain_id, zone_code, person_id, arrived_at FROM evacuation_person "
                        + "WHERE chain_id = ? ORDER BY person_id", ARRIVAL_MAPPER, chainId);
    }

    public void markEvacConfirmed(String chainNo, LocalDateTime confirmTime) {
        jdbcTemplate.update(
                "UPDATE disposal_chain SET evac_status = 'CONFIRMED', evac_confirm_time = ? WHERE chain_no = ?",
                Timestamp.valueOf(confirmTime), chainNo);
    }

    public void markEvacTimeout(String chainNo) {
        jdbcTemplate.update(
                "UPDATE disposal_chain SET evac_status = 'TIMEOUT' WHERE chain_no = ? AND evac_status = 'PENDING'",
                chainNo);
    }

    public void insertResume(ResumeAssessmentRecord record) {
        jdbcTemplate.update(
                "INSERT INTO resume_assessment (chain_id, apply_request_id, apply_time, safe_since, status) "
                        + "VALUES (?, ?, ?, ?, 'APPLIED')",
                record.getChainId(), record.getApplyRequestId(),
                Timestamp.valueOf(record.getApplyTime()), Timestamp.valueOf(record.getSafeSince()));
    }

    public ResumeAssessmentRecord findResumeByChain(long chainId) {
        List<ResumeAssessmentRecord> list = jdbcTemplate.query(
                "SELECT id, chain_id, apply_request_id, apply_time, safe_since, status, site_confirmed, "
                        + "monitor_confirmed, decide_request_id, decide_time, decided_at, remark "
                        + "FROM resume_assessment WHERE chain_id = ?", RESUME_MAPPER, chainId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 前次驳回后满足安全窗口可重新申请：重置评估行并保留同链唯一约束。
     */
    public void reopenResume(long chainId, String applyRequestId, LocalDateTime applyTime,
                             LocalDateTime safeSince) {
        jdbcTemplate.update(
                "UPDATE resume_assessment SET apply_request_id = ?, apply_time = ?, safe_since = ?, "
                        + "status = 'APPLIED', site_confirmed = NULL, monitor_confirmed = NULL, "
                        + "decide_request_id = NULL, decide_time = NULL, remark = NULL WHERE chain_id = ?",
                applyRequestId, Timestamp.valueOf(applyTime), Timestamp.valueOf(safeSince), chainId);
    }

    /**
     * 双确认结论落库，并回写处置链复工状态与决定时刻。
     */
    public void saveResumeDecision(long chainId, String chainNo, String decideRequestId,
                                   LocalDateTime decideTime, boolean siteConfirmed,
                                   boolean monitorConfirmed, String status, String remark) {
        jdbcTemplate.update(
                "UPDATE resume_assessment SET status = ?, site_confirmed = ?, monitor_confirmed = ?, "
                        + "decide_request_id = ?, decide_time = ?, remark = ? WHERE chain_id = ?",
                status, siteConfirmed ? 1 : 0, monitorConfirmed ? 1 : 0,
                decideRequestId, Timestamp.valueOf(decideTime), remark, chainId);
        jdbcTemplate.update(
                "UPDATE disposal_chain SET resume_status = ?, resume_decide_time = ? WHERE chain_no = ?",
                status, Timestamp.valueOf(decideTime), chainNo);
    }

    public void markResumeApplied(String chainNo, LocalDateTime applyTime) {
        jdbcTemplate.update(
                "UPDATE disposal_chain SET resume_status = 'APPLIED', resume_apply_time = ? WHERE chain_no = ?",
                Timestamp.valueOf(applyTime), chainNo);
    }

    /**
     * 归档（终态）：释放测区执行中槽位（active_key 置 NULL）。
     */
    public void archiveChain(String chainNo, LocalDateTime archiveTime) {
        jdbcTemplate.update(
                "UPDATE disposal_chain SET status = 'ARCHIVED', active_key = NULL, archive_time = ? "
                        + "WHERE chain_no = ? AND status = 'ACTIVE'",
                Timestamp.valueOf(archiveTime), chainNo);
    }

    private String chainSelect() {
        return "SELECT id, chain_no, zone_code, request_id, alarm_level, trigger_time, status, active_key, "
                + "evac_status, evac_confirm_time, resume_status, resume_apply_time, resume_decide_time, "
                + "archive_time, created_at FROM disposal_chain";
    }
}
