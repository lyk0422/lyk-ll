package com.example.starter.survey.data;

import com.example.starter.survey.domain.Defect;
import com.example.starter.survey.domain.DefectSeverity;
import com.example.starter.survey.domain.DefectStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 缺陷项与整改复查链数据访问。 */
@Repository
public class DefectRepository {

    private final JdbcTemplate jdbc;

    public DefectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Defect> MAPPER = (rs, n) -> new Defect(
            rs.getLong("id"),
            rs.getLong("survey_id"),
            DefectSeverity.valueOf(rs.getString("severity")),
            rs.getString("description"),
            rs.getDate("found_date").toLocalDate(),
            DefectStatus.valueOf(rs.getString("status")),
            rs.getObject("deadline") == null ? null : rs.getDate("deadline").toLocalDate(),
            rs.getObject("submitted_at") == null ? null : rs.getDate("submitted_at").toLocalDate(),
            rs.getObject("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant(),
            rs.getString("note")
    );

    /** 登轮时登记缺陷；一般缺陷写入发现日+30 日的复查截止日（含当日），重大缺陷截止日为 null。 */
    public Defect insert(long surveyId, DefectSeverity severity, String description,
                         LocalDate foundDate, LocalDate deadline,
                         String requestId, String fingerprint, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO defect (survey_id, severity, description, found_date, status,
                                                deadline, submitted_at, reviewed_at, note,
                                                request_id, fingerprint)
                            VALUES (?, ?, ?, ?, 'OPEN', ?, NULL, NULL, NULL, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, surveyId);
            ps.setString(2, severity.name());
            ps.setString(3, description);
            ps.setObject(4, Date.valueOf(foundDate));
            ps.setObject(5, deadline == null ? null : Date.valueOf(deadline));
            ps.setString(6, requestId);
            ps.setString(7, fingerprint);
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return findById(id).orElseThrow();
    }

    /** 按主键查找。 */
    public Optional<Defect> findById(long id) {
        return jdbc.query("SELECT * FROM defect WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 检验下的缺陷链（按发现顺序）。 */
    public List<Defect> findBySurvey(long surveyId) {
        return jdbc.query("SELECT * FROM defect WHERE survey_id = ? ORDER BY id", MAPPER, surveyId);
    }

    /** 限期内提交：受理为已整改（恰第 30 日提交同样受理）。 */
    public void markRectified(long defectId, LocalDate submittedAt, String note, Instant reviewedAt) {
        int updated = jdbc.update("""
                        UPDATE defect
                        SET status = 'RECTIFIED', submitted_at = ?, reviewed_at = ?, note = ?
                        WHERE id = ? AND status = 'OPEN'
                        """,
                Date.valueOf(submittedAt), Timestamp.from(reviewedAt), note, defectId);
        if (updated != 1) {
            throw new IllegalStateException("缺陷受理更新行数异常: " + updated);
        }
    }

    /** 逾期提交：记录提交日但不予受理。 */
    public void markRejectedLate(long defectId, LocalDate submittedAt, String note, Instant reviewedAt) {
        int updated = jdbc.update("""
                        UPDATE defect
                        SET status = 'REJECTED_LATE', submitted_at = ?, reviewed_at = ?, note = ?
                        WHERE id = ? AND status = 'OPEN'
                        """,
                Date.valueOf(submittedAt), Timestamp.from(reviewedAt), note, defectId);
        if (updated != 1) {
            throw new IllegalStateException("缺陷逾期更新行数异常: " + updated);
        }
    }
}
