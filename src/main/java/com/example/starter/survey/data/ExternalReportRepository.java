package com.example.starter.survey.data;

import com.example.starter.survey.domain.ExternalReport;
import com.example.starter.survey.domain.ExternalReportItem;
import com.example.starter.survey.domain.ExternalReportStatus;
import com.example.starter.survey.domain.ItemResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 外部检查报告修订链数据访问。
 * 唯一索引 uq_ext_report_revision(report_no, revision_no) 保证同一修订只落一行；
 * 更正以“新修订行 + 旧行置 SUPERSEDED”方式留痕，历史行不可更新删除。
 */
@Repository
public class ExternalReportRepository {

    private final JdbcTemplate jdbc;

    public ExternalReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ExternalReport> REPORT_MAPPER = (rs, n) -> new ExternalReport(
            rs.getLong("id"),
            rs.getString("report_no"),
            rs.getInt("revision_no"),
            rs.getString("ship_imo"),
            rs.getString("flag_code"),
            rs.getTimestamp("completed_at").toInstant(),
            rs.getString("signature_marker"),
            ExternalReportStatus.valueOf(rs.getString("status")),
            rs.getObject("superseded_by") == null ? null : rs.getLong("superseded_by"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<ExternalReportItem> ITEM_MAPPER = (rs, n) ->
            new ExternalReportItem(rs.getLong("id"), rs.getLong("report_id"),
                    rs.getString("item_code"), ItemResult.valueOf(rs.getString("result")),
                    rs.getString("note"));

    /** 同报告同修订唯一索引冲突。 */
    public static class RevisionConflict extends RuntimeException {
        public RevisionConflict(Throwable cause) {
            super(cause);
        }
    }

    /** 插入一条新修订行（不处理旧行状态，由 {@link #markSuperseded} 同事务完成）。 */
    public ExternalReport insertRevision(String reportNo, int revisionNo, String shipImo,
                                         String flagCode, Instant completedAt,
                                         String signatureMarker, String requestId,
                                         String fingerprint, Instant now) {
        try {
            jdbc.update("""
                            INSERT INTO external_report
                                (report_no, revision_no, ship_imo, flag_code, completed_at,
                                 signature_marker, status, superseded_by, request_id,
                                 fingerprint, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, 'CURRENT', NULL, ?, ?, ?)
                            """,
                    reportNo, revisionNo, shipImo, flagCode, Timestamp.from(completedAt),
                    signatureMarker, requestId, fingerprint, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            throw new RevisionConflict(e);
        }
        return findByReportNoAndRevision(reportNo, revisionNo).orElseThrow();
    }

    /** 写入修订行的逐项结论。 */
    public void insertItem(long reportId, String itemCode, ItemResult result, String note) {
        jdbc.update("""
                        INSERT INTO external_report_item (report_id, item_code, result, note)
                        VALUES (?, ?, ?, ?)
                        """, reportId, itemCode, result.name(), note);
    }

    /** 旧修订置 SUPERSEDED 并记录替代它的新修订行。 */
    public void markSuperseded(long oldReportId, long newReportId) {
        int updated = jdbc.update("""
                        UPDATE external_report
                        SET status = 'SUPERSEDED', superseded_by = ?
                        WHERE id = ? AND status = 'CURRENT'
                        """, newReportId, oldReportId);
        if (updated != 1) {
            throw new IllegalStateException("旧报告修订失效更新行数异常: " + updated);
        }
    }

    /** 按主键查找。 */
    public Optional<ExternalReport> findById(long id) {
        return jdbc.query("SELECT * FROM external_report WHERE id = ?", REPORT_MAPPER, id)
                .stream().findFirst();
    }

    /** 按主键行锁（发证与更正并发时冻结修订）。 */
    public Optional<ExternalReport> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM external_report WHERE id = ? FOR UPDATE",
                        REPORT_MAPPER, id)
                .stream().findFirst();
    }

    /** 按报告编号 + 修订号查找。 */
    public Optional<ExternalReport> findByReportNoAndRevision(String reportNo, int revisionNo) {
        return jdbc.query(
                        "SELECT * FROM external_report WHERE report_no = ? AND revision_no = ?",
                        REPORT_MAPPER, reportNo, revisionNo)
                .stream().findFirst();
    }

    /** 查找报告当前最新修订（不加锁）。 */
    public Optional<ExternalReport> findCurrentByReportNo(String reportNo) {
        return jdbc.query("""
                        SELECT * FROM external_report
                        WHERE report_no = ? AND status = 'CURRENT'
                        """, REPORT_MAPPER, reportNo)
                .stream().findFirst();
    }

    /** 行锁报告当前最新修订（更正/发证并发互斥用）。 */
    public Optional<ExternalReport> findCurrentByReportNoForUpdate(String reportNo) {
        return jdbc.query("""
                        SELECT * FROM external_report
                        WHERE report_no = ? AND status = 'CURRENT' FOR UPDATE
                        """, REPORT_MAPPER, reportNo)
                .stream().findFirst();
    }

    /** 报告的全部修订（按修订号升序，证据链用）。 */
    public List<ExternalReport> findRevisions(String reportNo) {
        return jdbc.query(
                "SELECT * FROM external_report WHERE report_no = ? ORDER BY revision_no",
                REPORT_MAPPER, reportNo);
    }

    /** 某修订行的逐项结论（按项目代码升序）。 */
    public List<ExternalReportItem> findItems(long reportId) {
        return jdbc.query(
                "SELECT * FROM external_report_item WHERE report_id = ? ORDER BY item_code",
                ITEM_MAPPER, reportId);
    }
}
