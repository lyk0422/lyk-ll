package com.example.starter.pension.repo;

import com.example.starter.pension.domain.MemberStatus;
import com.example.starter.pension.po.MemberRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 成员表数据访问。
 */
@Repository
public class MemberRepository {

    private static final RowMapper<MemberRow> ROW_MAPPER = (rs, rowNum) -> new MemberRow(
            rs.getLong("id"),
            rs.getString("member_no"),
            rs.getString("name"),
            rs.getDate("hire_date").toLocalDate(),
            MemberStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public MemberRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    /**
     * 按成员编号查询成员。
     */
    public Optional<MemberRow> findByNo(String memberNo) {
        return jdbcClient.sql("SELECT id, member_no, name, hire_date, status FROM member WHERE member_no = :memberNo")
                .param("memberNo", memberNo)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * 按主键查询并对成员行加排他锁（SELECT ... FOR UPDATE），串行化同一成员的并发支付受理。
     */
    public Optional<MemberRow> lockById(long memberId) {
        return jdbcClient.sql("SELECT id, member_no, name, hire_date, status FROM member WHERE id = :id FOR UPDATE")
                .param("id", memberId)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * 按主键查询成员。
     */
    public Optional<MemberRow> findById(long memberId) {
        return jdbcClient.sql("SELECT id, member_no, name, hire_date, status FROM member WHERE id = :id")
                .param("id", memberId)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * 新增成员；成员编号已存在时更新姓名/入职日/状态。
     *
     * @return 成员主键
     */
    public long upsert(String memberNo, String name, LocalDate hireDate, MemberStatus status) {
        Long existingId = jdbcClient.sql("SELECT id FROM member WHERE member_no = :memberNo")
                .param("memberNo", memberNo)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (existingId != null) {
            jdbcClient.sql("""
                            UPDATE member
                               SET name = :name, hire_date = :hireDate, status = :status
                             WHERE id = :id""")
                    .param("name", name)
                    .param("hireDate", Date.valueOf(hireDate))
                    .param("status", status.name())
                    .param("id", existingId)
                    .update();
            return existingId;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO member (member_no, name, hire_date, status) VALUES (?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, memberNo);
            ps.setString(2, name);
            ps.setDate(3, Date.valueOf(hireDate));
            ps.setString(4, status.name());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
