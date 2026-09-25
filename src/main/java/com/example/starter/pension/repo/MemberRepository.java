package com.example.starter.pension.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.pension.domain.EmploymentStatus;

/**
 * 成员表数据访问。
 */
@Repository
public class MemberRepository {

    /**
     * 成员持久化对象。
     */
    public record MemberPo(
            long id,
            String memberNo,
            String name,
            LocalDate hireDate,
            EmploymentStatus status,
            Instant createdAtUtc) {
    }

    private static final RowMapper<MemberPo> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public MemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新增成员；成员编号重复时返回 {@code null}（由调用方转为冲突错误）。
     */
    public int insert(MemberPo po) {
        return jdbc.update(
                "INSERT INTO pension_member (member_no, name, hire_date, employment_status) "
                        + "VALUES (?, ?, ?, ?)",
                po.memberNo(), po.name(), po.hireDate(), po.status().name());
    }

    /**
     * 按成员编号查询。
     */
    public Optional<MemberPo> findByNo(String memberNo) {
        return jdbc.query("SELECT id, member_no, name, hire_date, employment_status, created_at_utc "
                + "FROM pension_member WHERE member_no = ?", MAPPER, memberNo).stream().findFirst();
    }

    /**
     * 行级锁定成员（SELECT ... FOR UPDATE），串行化同一成员的并发支付/补发受理。
     */
    public MemberPo lockByNo(String memberNo) {
        return jdbc.queryForObject(
                "SELECT id, member_no, name, hire_date, employment_status, created_at_utc "
                        + "FROM pension_member WHERE member_no = ? FOR UPDATE",
                MAPPER, memberNo);
    }

    private static MemberPo map(ResultSet rs) throws SQLException {
        return new MemberPo(
                rs.getLong("id"),
                rs.getString("member_no"),
                rs.getString("name"),
                rs.getObject("hire_date", LocalDate.class),
                EmploymentStatus.valueOf(rs.getString("employment_status")),
                rs.getTimestamp("created_at_utc").toInstant());
    }
}
