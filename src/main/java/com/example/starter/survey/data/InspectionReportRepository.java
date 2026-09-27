package com.example.starter.survey.data;

import com.example.starter.survey.domain.InspectionReport;
import com.example.starter.survey.domain.InspectionReportItem;
import com.example.starter.survey.domain.ItemConclusion;
import com.example.starter.survey.domain.ReportStatus;
import com.example.starter.survey.domain.SurveyItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 外部检查报告数据访问。
 * active_flag='Y' 配合唯一索引 uq_report_active 保证：同一报告编号同一时刻至多一个有效修订；
 * 更正时“旧修订失效 → 新修订生效”与互认失效/风险登记在同一事务内完成。
 */
@Repository
public class InspectionReportRepository {

    private final JdbcTemplate jdbc;

    public InspectionReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<InspectionReport> MAPPER = (rs, n) -> new InspectionReport(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("report_no"),
            rs.getString("ship_imo"),
            rs.getString("flag_code"),
            rs.getTimestamp("completed_at").toInstant(),
            rs.getString("attestation"),
            rs.getInt("revision"),
            rs.getInt("version"),
            ReportStatus.valueOf(rs.getString("status")),
            rs.getObject("supersedes_id") == null ? null : rs.getLong("supersedes_id"),
            rs.getString("content_fingerprint"),
            rs.getString("fingerprint"),
            rs.getTimestamp("created_at").toInstant()
    );

    private static final RowMapper<InspectionReportItem> ITEM_MAPPER = (rs, n) -> new InspectionReportItem(
            rs.getLong("id"),
            rs.getLong("report_id"),
            SurveyItem.valueOf(rs.getString("item_code")),
            ItemConclusion.valueOf(rs.getString("conclusion"))
    );

    /**
     * 挂起插入新修订（active_flag 为 NULL，不占有效修订唯一槽位）。
     * 首修订由调用方随后 {@link #activate}；更正按“挂起插入 → 旧修订失效 → 新修订生效”顺序。
     */
    public InspectionReport insert(String requestId, String reportNo, String shipImo,
                                   String flagCode, Instant completedAt, String attestation,
                                   int revision, int version, Long supersedesId,
                                   String contentFingerprint, String fingerprint,
                                   List<ReportItemRow> items, Instant now) {
        jdbc.update("""
                        INSERT INTO inspection_report
                            (request_id, report_no, ship_imo, flag_code, completed_at, attestation,
                             revision, version, status, supersedes_id,
                             content_fingerprint, fingerprint, created_at, active_flag)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, NULL)
                        """,
                requestId, reportNo, shipImo, flagCode, Timestamp.from(completedAt), attestation,
                revision, version, supersedesId,
                contentFingerprint, fingerprint, Timestamp.from(now));
        InspectionReport report = jdbc.query(
                        "SELECT * FROM inspection_report WHERE request_id = ?",
                        MAPPER, requestId)
                .stream().findFirst().orElseThrow();
        for (ReportItemRow item : items) {
            jdbc.update("""
                            INSERT INTO inspection_report_item (report_id, item_code, conclusion)
                            VALUES (?, ?, ?)
                            """,
                    report.id(), item.itemCode().name(), item.conclusion().name());
        }
        return report;
    }

    /** 挂起修订转为当前有效修订（占用有效修订唯一槽位，并发冲突由调用方处理）。 */
    public void activate(long reportId) {
        int updated = jdbc.update(
                "UPDATE inspection_report SET active_flag = 'Y' WHERE id = ?", reportId);
        if (updated != 1) {
            throw new IllegalStateException("报告修订生效更新行数异常: " + updated);
        }
    }

    /** 报告逐项结论写入参数。 */
    public record ReportItemRow(SurveyItem itemCode, ItemConclusion conclusion) {
    }

    /** 按报告编号查找当前有效修订（不加锁）。 */
    public Optional<InspectionReport> findActiveByReportNo(String reportNo) {
        return jdbc.query("""
                        SELECT * FROM inspection_report
                        WHERE report_no = ? AND active_flag = 'Y'
                        """, MAPPER, reportNo)
                .stream().findFirst();
    }

    /**
     * 在事务内行锁当前有效修订（FOR UPDATE）。
     * 申请互认、报告更正与发证门禁据此冻结一致的修订号。
     */
    public Optional<InspectionReport> findActiveByReportNoForUpdate(String reportNo) {
        return jdbc.query("""
                        SELECT * FROM inspection_report
                        WHERE report_no = ? AND active_flag = 'Y'
                        FOR UPDATE
                        """, MAPPER, reportNo)
                .stream().findFirst();
    }

    /** 按主键行锁报告修订（FOR UPDATE），发证门禁按主键升序锁定以保持一致顺序。 */
    public Optional<InspectionReport> findByIdForUpdate(long id) {
        return jdbc.query("""
                        SELECT * FROM inspection_report
                        WHERE id = ?
                        FOR UPDATE
                        """, MAPPER, id)
                .stream().findFirst();
    }

    /** 列出报告全部修订（按修订号升序）。 */
    public List<InspectionReport> findRevisions(String reportNo) {
        return jdbc.query("""
                        SELECT * FROM inspection_report
                        WHERE report_no = ? ORDER BY revision
                        """, MAPPER, reportNo);
    }

    /** 旧修订失效：状态 SUPERSEDED、释放有效修订唯一槽位。 */
    public void markSuperseded(long reportId) {
        int updated = jdbc.update("""
                        UPDATE inspection_report
                        SET status = 'SUPERSEDED', active_flag = NULL
                        WHERE id = ? AND active_flag = 'Y'
                        """, reportId);
        if (updated != 1) {
            throw new IllegalStateException("报告修订失效更新行数异常: " + updated);
        }
    }

    /** 报告修订的逐项结论。 */
    public List<InspectionReportItem> findItems(long reportId) {
        return jdbc.query("""
                        SELECT * FROM inspection_report_item
                        WHERE report_id = ? ORDER BY item_code
                        """, ITEM_MAPPER, reportId);
    }
}
