package com.example.starter.transition.repo;

import com.example.starter.transition.po.RollJudgement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 过渡卷判定链数据访问；唯一约束 (roll_no, round_no) 保证改判链可复算且不重复。
 */
@Repository
public class JudgementRepository {

    private static final RowMapper<RollJudgement> MAPPER = new JudgementRowMapper();

    private final JdbcTemplate jdbc;

    public JudgementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RollJudgement judgement) {
        jdbc.update("INSERT INTO roll_judgement (roll_no, round_no, result, gate_passed, reason, request_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                judgement.rollNo, judgement.roundNo, judgement.result,
                judgement.gatePassed == null ? null : (judgement.gatePassed ? 1 : 0),
                judgement.reason, judgement.requestId);
    }

    public List<RollJudgement> findByRollNo(String rollNo) {
        return jdbc.query(
                "SELECT id, roll_no, round_no, result, gate_passed, reason, request_id, created_at "
                        + "FROM roll_judgement WHERE roll_no = ? ORDER BY round_no",
                MAPPER, rollNo);
    }

    private static final class JudgementRowMapper implements RowMapper<RollJudgement> {
        @Override
        public RollJudgement mapRow(ResultSet rs, int rowNum) throws SQLException {
            RollJudgement j = new RollJudgement();
            j.id = rs.getLong("id");
            j.rollNo = rs.getString("roll_no");
            j.roundNo = rs.getInt("round_no");
            j.result = rs.getString("result");
            int gate = rs.getInt("gate_passed");
            j.gatePassed = rs.wasNull() ? null : gate == 1;
            j.reason = rs.getString("reason");
            j.requestId = rs.getString("request_id");
            j.createdAt = rs.getTimestamp("created_at").toLocalDateTime();
            return j;
        }
    }
}
