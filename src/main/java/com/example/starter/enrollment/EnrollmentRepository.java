package com.example.starter.enrollment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 入组相关数据访问：试验/中心主数据、入组记录、区组分配计划、随机号计数器行锁。
 * 时间字段统一以 java.sql.Timestamp 存取，保证 UTC 口径与 JVM 时区无关的往返一致。
 */
@Repository
public class EnrollmentRepository {

    private final JdbcTemplate jdbc;

    public EnrollmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Trial findTrialByCode(String code) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, code, treat_code, control_code FROM trial WHERE code = ?",
                    (rs, rowNum) -> new Trial(rs.getLong("id"), rs.getString("code"),
                            rs.getString("treat_code").charAt(0), rs.getString("control_code").charAt(0)),
                    code);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public Center findCenterByCode(String code) {
        try {
            return jdbc.queryForObject(
                    "SELECT id, code FROM center WHERE code = ?",
                    (rs, rowNum) -> new Center(rs.getLong("id"), rs.getString("code")),
                    code);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public Enrollment findByRequestId(long trialId, String requestId) {
        return queryOne("SELECT * FROM enrollment WHERE trial_id = ? AND request_id = ?", trialId, requestId);
    }

    public Enrollment findByScreening(long trialId, String screeningNo) {
        return queryOne("SELECT * FROM enrollment WHERE trial_id = ? AND screening_no = ?", trialId, screeningNo);
    }

    public void insert(Enrollment e) {
        jdbc.update(
                "INSERT INTO enrollment (trial_id, request_id, fingerprint, screening_no, center_id,"
                        + " consent_signed_at, random_no, blinded_arm, actual_arm, status, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                e.trialId(), e.requestId(), e.fingerprint(), e.screeningNo(), e.centerId(),
                Timestamp.from(e.consentSignedAt()), e.randomNo(), String.valueOf(e.blindedArm()),
                String.valueOf(e.actualArm()), e.status(), Timestamp.from(e.createdAt()));
    }

    public void updateStatus(long trialId, String screeningNo, String status) {
        jdbc.update("UPDATE enrollment SET status = ? WHERE trial_id = ? AND screening_no = ?",
                status, trialId, screeningNo);
    }

    /**
     * 对中心计数器行加悲观锁，串行化该中心内的区组计划生成。
     */
    public void lockCounter(long trialId, long centerId) {
        jdbc.queryForObject(
                "SELECT next_no FROM random_counter WHERE trial_id = ? AND center_id = ? FOR UPDATE",
                Long.class, trialId, centerId);
    }

    public String findBlockArm(long trialId, long centerId, long blockNo, int position) {
        try {
            return jdbc.queryForObject(
                    "SELECT arm FROM randomization_block"
                            + " WHERE trial_id = ? AND center_id = ? AND block_no = ? AND position = ?",
                    String.class, trialId, centerId, blockNo, position);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 读取指定区组的完整计划（按位置升序）；区组不存在时返回空列表。
     */
    public List<String> blockArms(long trialId, long centerId, long blockNo) {
        return jdbc.query(
                "SELECT arm FROM randomization_block"
                        + " WHERE trial_id = ? AND center_id = ? AND block_no = ? ORDER BY position",
                (rs, rowNum) -> rs.getString("arm"), trialId, centerId, blockNo);
    }

    /**
     * 单语句插入整个区组计划（4 行），保证原子性；并发冲突时整体失败由调用方重读。
     */
    public void insertBlock(long trialId, long centerId, long blockNo, List<Character> arms) {
        jdbc.update(
                "INSERT INTO randomization_block (trial_id, center_id, block_no, position, arm)"
                        + " VALUES (?,?,?,0,?), (?,?,?,1,?), (?,?,?,2,?), (?,?,?,3,?)",
                trialId, centerId, blockNo, String.valueOf(arms.get(0)),
                trialId, centerId, blockNo, String.valueOf(arms.get(1)),
                trialId, centerId, blockNo, String.valueOf(arms.get(2)),
                trialId, centerId, blockNo, String.valueOf(arms.get(3)));
    }

    private Enrollment queryOne(String sql, Object... args) {
        try {
            return jdbc.queryForObject(sql, this::mapEnrollment, args);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private Enrollment mapEnrollment(ResultSet rs, int rowNum) throws SQLException {
        return new Enrollment(
                rs.getLong("id"),
                rs.getLong("trial_id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getString("screening_no"),
                rs.getLong("center_id"),
                rs.getTimestamp("consent_signed_at").toInstant(),
                rs.getLong("random_no"),
                rs.getString("blinded_arm").charAt(0),
                rs.getString("actual_arm").charAt(0),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }
}
