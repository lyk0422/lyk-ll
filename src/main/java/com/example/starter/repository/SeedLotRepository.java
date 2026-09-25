package com.example.starter.repository;

import com.example.starter.domain.Grade;
import com.example.starter.domain.LotStatus;
import com.example.starter.domain.SeedLot;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 种子批次表数据访问。时间列均为 UTC 无时区时间戳，读写统一按 UTC 转换。
 */
@Repository
public class SeedLotRepository {

    private static final RowMapper<SeedLot> ROW_MAPPER = (rs, rowNum) -> {
        Timestamp unqualifiedAt = rs.getTimestamp("unqualified_at");
        String gradeValue = rs.getString("determined_grade");
        return new SeedLot(
                rs.getLong("id"),
                rs.getString("lot_no"),
                rs.getString("applicant"),
                rs.getString("variety_name"),
                rs.getString("field_code"),
                rs.getBigDecimal("area_mu"),
                rs.getInt("available_labels"),
                LotStatus.valueOf(rs.getString("status")),
                gradeValue == null ? null : Grade.valueOf(gradeValue),
                rs.getInt("recheck_count"),
                unqualifiedAt == null ? null : unqualifiedAt.toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    };

    private final JdbcTemplate jdbcTemplate;

    public SeedLotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 新建批次，自增主键写回入参对象不可行（record 不可变），故返回带主键的新记录。 */
    public SeedLot insert(SeedLot lot) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO seed_lot
                        (lot_no, applicant, variety_name, field_code, area_mu, available_labels,
                         status, determined_grade, recheck_count, unqualified_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, lot.lotNo());
            ps.setString(2, lot.applicant());
            ps.setString(3, lot.varietyName());
            ps.setString(4, lot.fieldCode());
            ps.setBigDecimal(5, lot.areaMu());
            ps.setInt(6, lot.availableLabels());
            ps.setString(7, lot.status().name());
            ps.setString(8, lot.determinedGrade() == null ? null : lot.determinedGrade().name());
            ps.setInt(9, lot.recheckCount());
            if (lot.unqualifiedAt() == null) {
                ps.setTimestamp(10, null);
            } else {
                ps.setTimestamp(10, Timestamp.from(lot.unqualifiedAt()));
            }
            ps.setTimestamp(11, Timestamp.from(lot.createdAt()));
            ps.setTimestamp(12, Timestamp.from(lot.updatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0L : key.longValue();
        return new SeedLot(id, lot.lotNo(), lot.applicant(), lot.varietyName(), lot.fieldCode(),
                lot.areaMu(), lot.availableLabels(), lot.status(), lot.determinedGrade(),
                lot.recheckCount(), lot.unqualifiedAt(), lot.createdAt(), lot.updatedAt());
    }

    public SeedLot findByLotNo(String lotNo) {
        List<SeedLot> lots = jdbcTemplate.query(
                "SELECT * FROM seed_lot WHERE lot_no = ?", ROW_MAPPER, lotNo);
        return lots.isEmpty() ? null : lots.get(0);
    }

    /** 按批次号加行锁读取，供检验/复检等按批次串行化的事务使用。 */
    public SeedLot findByLotNoForUpdate(String lotNo) {
        List<SeedLot> lots = jdbcTemplate.query(
                "SELECT * FROM seed_lot WHERE lot_no = ? FOR UPDATE", ROW_MAPPER, lotNo);
        return lots.isEmpty() ? null : lots.get(0);
    }

    public SeedLot findById(long id) {
        List<SeedLot> lots = jdbcTemplate.query(
                "SELECT * FROM seed_lot WHERE id = ?", ROW_MAPPER, id);
        return lots.isEmpty() ? null : lots.get(0);
    }

    /** 行级锁读取，供发证/注销/结论更新等需要串行化的事务使用。 */
    public SeedLot findByIdForUpdate(long id) {
        List<SeedLot> lots = jdbcTemplate.query(
                "SELECT * FROM seed_lot WHERE id = ? FOR UPDATE", ROW_MAPPER, id);
        return lots.isEmpty() ? null : lots.get(0);
    }

    /** 全字段更新（状态机推进后落库）。 */
    public void update(SeedLot lot) {
        int rows = jdbcTemplate.update("""
                UPDATE seed_lot
                   SET available_labels = ?,
                       status = ?,
                       determined_grade = ?,
                       recheck_count = ?,
                       unqualified_at = ?,
                       updated_at = ?
                 WHERE id = ?
                """,
                lot.availableLabels(),
                lot.status().name(),
                lot.determinedGrade() == null ? null : lot.determinedGrade().name(),
                lot.recheckCount(),
                lot.unqualifiedAt() == null ? null : Timestamp.from(lot.unqualifiedAt()),
                Timestamp.from(lot.updatedAt()),
                lot.id());
        if (rows != 1) {
            throw new IllegalStateException("批次更新行数异常: " + rows);
        }
    }

    /**
     * 条件核减可用标签：仅当批次处于 QUALIFIED 且库存充足时成功。
     *
     * @param now 服务端当前时间（UTC，由可控时钟提供）
     * @return 影响行数；0 表示标签不足或状态已变，调用方须回滚发证事务
     */
    public int deductLabelsConditionally(long lotId, int labelCount, java.time.Instant now) {
        return jdbcTemplate.update("""
                UPDATE seed_lot
                   SET available_labels = available_labels - ?,
                       updated_at = ?
                 WHERE id = ?
                   AND status = 'QUALIFIED'
                   AND available_labels >= ?
                """,
                labelCount, Timestamp.from(now), lotId, labelCount);
    }

    /**
     * 条件推进为已发证：仅当批次仍为 QUALIFIED 时成功。
     *
     * @return 影响行数；0 表示批次状态已变化（如并发注销），调用方回滚发证事务
     */
    public int markCertifiedIfQualified(long lotId, java.time.Instant now) {
        return jdbcTemplate.update("""
                UPDATE seed_lot
                   SET status = 'CERTIFIED',
                       updated_at = ?
                 WHERE id = ?
                   AND status = 'QUALIFIED'
                """,
                Timestamp.from(now), lotId);
    }
}
