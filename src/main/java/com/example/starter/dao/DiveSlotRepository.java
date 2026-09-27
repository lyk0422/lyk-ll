package com.example.starter.dao;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 潜水员受控下潜占用槽：主键即潜水员工号。
 * 两组共享同一潜水员并发下潜时，INSERT 由数据库唯一受理，败方抛
 * {@link DuplicateKeyException}。
 */
@Repository
public class DiveSlotRepository {

    private static final RowMapper<DiveSlotRow> MAPPER = (rs, n) -> new DiveSlotRow(
            rs.getString("diver_code"),
            rs.getString("dive_request_id"),
            rs.getTimestamp("occupied_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public DiveSlotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 占用槽位；同潜次重做方案时重复占用是幂等的，异潜次占用由调用方按冲突处理。 */
    public void insert(DiveSlotRow row) {
        jdbc.update("INSERT INTO diver_dive_slot (diver_code, dive_request_id, occupied_at_utc) "
                        + "VALUES (?, ?, ?)",
                row.diverCode(), row.diveRequestId(), Timestamp.from(row.occupiedAtUtc()));
    }

    public Optional<DiveSlotRow> find(String diverCode) {
        return jdbc.query("SELECT * FROM diver_dive_slot WHERE diver_code = ?",
                MAPPER, diverCode).stream().findFirst();
    }

    /** 受控出水结算同事务释放某潜次占用的全部槽位（两名组员）。 */
    public int releaseByDive(String diveRequestId) {
        return jdbc.update("DELETE FROM diver_dive_slot WHERE dive_request_id = ?", diveRequestId);
    }
}
