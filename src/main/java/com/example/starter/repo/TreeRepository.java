package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.RiskCount;
import com.example.starter.domain.TreeRow;

/**
 * 行道树档案数据访问。
 */
@Repository
public class TreeRepository {

    private static final RowMapper<TreeRow> MAPPER = (rs, n) -> map(rs);

    private final JdbcTemplate jdbc;

    public TreeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static TreeRow map(ResultSet rs) throws SQLException {
        return new TreeRow(rs.getLong("id"), rs.getLong("segment_id"), rs.getString("tree_code"),
                rs.getDouble("tilt_degrees"), rs.getInt("disease_level"), rs.getInt("age_years"),
                rs.getInt("wind_exposure"), rs.getDouble("risk_score"), rs.getInt("risk_level"),
                rs.getObject("evaluated_at", LocalDateTime.class));
    }

    public long insert(long segmentId, String treeCode, double tiltDegrees, int diseaseLevel,
                       int ageYears, int windExposure, double riskScore, int riskLevel,
                       LocalDateTime evaluatedAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO tree(segment_id, tree_code, tilt_degrees, disease_level, age_years,"
                            + " wind_exposure, risk_score, risk_level, evaluated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, segmentId);
            ps.setString(2, treeCode);
            ps.setDouble(3, tiltDegrees);
            ps.setInt(4, diseaseLevel);
            ps.setInt(5, ageYears);
            ps.setInt(6, windExposure);
            ps.setDouble(7, riskScore);
            ps.setInt(8, riskLevel);
            ps.setObject(9, evaluatedAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<TreeRow> findByCode(String treeCode) {
        return jdbc.query("SELECT * FROM tree WHERE tree_code = ?", MAPPER, treeCode)
                .stream().findFirst();
    }

    public Optional<TreeRow> findById(long id) {
        return jdbc.query("SELECT * FROM tree WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public void updateAssessment(long id, double tiltDegrees, int diseaseLevel, int ageYears,
                                 int windExposure, double riskScore, int riskLevel,
                                 LocalDateTime evaluatedAt) {
        jdbc.update("UPDATE tree SET tilt_degrees = ?, disease_level = ?, age_years = ?,"
                        + " wind_exposure = ?, risk_score = ?, risk_level = ?, evaluated_at = ?"
                        + " WHERE id = ?",
                tiltDegrees, diseaseLevel, ageYears, windExposure, riskScore, riskLevel,
                evaluatedAt, id);
    }

    /**
     * 各路段树木风险等级分布。
     */
    public List<RiskCount> countBySegmentAndLevel() {
        return jdbc.query(
                "SELECT segment_id, risk_level, COUNT(*) AS cnt FROM tree"
                        + " GROUP BY segment_id, risk_level",
                (rs, n) -> new RiskCount(rs.getLong("segment_id"), rs.getInt("risk_level"),
                        rs.getLong("cnt")));
    }

    public long count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tree", Long.class);
    }
}
