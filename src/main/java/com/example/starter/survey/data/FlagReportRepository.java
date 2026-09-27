package com.example.starter.survey.data;

import com.example.starter.survey.domain.FlagInspectionReport;
import com.example.starter.survey.domain.FlagItemConclusion;
import com.example.starter.survey.domain.FlagReportItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 外部船旗国检查报告数据访问。
 * 报告编号全局唯一；更正插入新修订行，旧修订保留供已签发证据链追溯。
 */
@Repository
public class FlagReportRepository {

    private final JdbcTemplate jdbc;

    public FlagReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<FlagInspectionReport> REPORT_MAPPER = (rs, n) -> new FlagInspectionReport(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("report_no"),
            rs.getInt("revision"),
            rs.getString("imo"),
            rs.getString("flag_code"),
            rs.getTimestamp("completed_at").toInstant(),
            rs.getString("synthetic_signoff"),
            rs.getTimestamp("created_at").toInstant()
    );

    private static final RowMapper<FlagReportItem> ITEM_MAPPER = (rs, n) -> new FlagReportItem(
            rs.getLong("id"),
            rs.getLong("report_id"),
            rs.getString("flag_item_code"),
            FlagItemConclusion.valueOf(rs.getString("conclusion"))
    );

    /** 插入报告修订（导入为修订 1，更正为最新修订+1）。 */
    public FlagInspectionReport insertRevision(String requestId, String reportNo, int revision,
                                               String imo, String flagCode, Instant completedAt,
                                               String syntheticSignoff, Instant now) {
        jdbc.update("""
                        INSERT INTO flag_inspection_report (request_id, report_no, revision, imo,
                                                            flag_code, completed_at,
                                                            synthetic_signoff, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                requestId, reportNo, revision, imo, flagCode,
                Timestamp.from(completedAt), syntheticSignoff, Timestamp.from(now));
        return findByReportNoAndRevision(reportNo, revision).orElseThrow();
    }

    /** 插入报告逐项结论。 */
    public void insertItem(long reportId, String flagItemCode, FlagItemConclusion conclusion) {
        jdbc.update("""
                        INSERT INTO flag_report_item (report_id, flag_item_code, conclusion)
                        VALUES (?, ?, ?)
                        """,
                reportId, flagItemCode, conclusion.name());
    }

    /** 按主键查找报告修订。 */
    public Optional<FlagInspectionReport> findById(long id) {
        return jdbc.query("SELECT * FROM flag_inspection_report WHERE id = ?", REPORT_MAPPER, id)
                .stream().findFirst();
    }

    /** 按报告编号+修订号查找。 */
    public Optional<FlagInspectionReport> findByReportNoAndRevision(String reportNo, int revision) {
        return jdbc.query("""
                        SELECT * FROM flag_inspection_report
                        WHERE report_no = ? AND revision = ?
                        """, REPORT_MAPPER, reportNo, revision)
                .stream().findFirst();
    }

    /** 查找报告当前最新修订。 */
    public Optional<FlagInspectionReport> findLatestRevision(String reportNo) {
        return jdbc.query("""
                        SELECT * FROM flag_inspection_report
                        WHERE report_no = ? ORDER BY revision DESC LIMIT 1
                        """, REPORT_MAPPER, reportNo)
                .stream().findFirst();
    }

    /** 列出报告全部修订（升序）。 */
    public List<FlagInspectionReport> findRevisions(String reportNo) {
        return jdbc.query("""
                        SELECT * FROM flag_inspection_report
                        WHERE report_no = ? ORDER BY revision
                        """, REPORT_MAPPER, reportNo);
    }

    /** 列出某修订的逐项结论。 */
    public List<FlagReportItem> findItems(long reportId) {
        return jdbc.query("""
                        SELECT * FROM flag_report_item
                        WHERE report_id = ? ORDER BY flag_item_code
                        """, ITEM_MAPPER, reportId);
    }
}
