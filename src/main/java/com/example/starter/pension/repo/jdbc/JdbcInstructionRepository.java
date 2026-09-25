package com.example.starter.pension.repo.jdbc;

import com.example.starter.pension.po.InstructionRow;
import com.example.starter.pension.repo.InstructionRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 支付指令 JDBC 实现。
 */
@Repository
public class JdbcInstructionRepository implements InstructionRepository {

    private static final String COLUMNS =
            "id, request_id, plan_id, installment_id, seq_no, amount_cents, fingerprint, success, created_at";

    private static final RowMapper<InstructionRow> ROW_MAPPER = (rs, rowNum) -> new InstructionRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("plan_id"),
            rs.getLong("installment_id"),
            rs.getInt("seq_no"),
            rs.getLong("amount_cents"),
            rs.getString("fingerprint"),
            rs.getBoolean("success"),
            rs.getTimestamp("created_at").toInstant().atOffset(ZoneOffset.UTC));

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public JdbcInstructionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Override
    public long insert(InstructionRow in) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO payout_instruction "
                            + "(request_id, plan_id, installment_id, seq_no, amount_cents, "
                            + "fingerprint, success) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, in.requestId());
            ps.setLong(2, in.planId());
            ps.setLong(3, in.installmentId());
            ps.setInt(4, in.seqNo());
            ps.setLong(5, in.amountCents());
            ps.setString(6, in.fingerprint());
            ps.setBoolean(7, in.success());
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Override
    public Optional<InstructionRow> findByRequestId(String requestId) {
        return jdbcClient.sql("SELECT " + COLUMNS
                        + " FROM payout_instruction WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(ROW_MAPPER)
                .optional();
    }
}
