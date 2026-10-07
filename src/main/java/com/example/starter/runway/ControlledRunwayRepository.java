package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 受控跑道登记数据访问：controlled_runway 表。
 */
@Repository
public class ControlledRunwayRepository {

    private static final RowMapper<ControlledRunway> MAPPER = (rs, rowNum) -> new ControlledRunway(
            rs.getString("runway_id"),
            rs.getInt("length_meters"),
            rs.getInt("version"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public ControlledRunwayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记受控跑道，初始版本1；跑道编号主键冲突时抛 DuplicateKeyException。 */
    public void insert(ControlledRunway runway) {
        jdbc.update("INSERT INTO controlled_runway (runway_id, length_meters, version, request_id,"
                        + " created_at, updated_at) VALUES (?,?,?,?,?,?)",
                runway.runwayId(), runway.lengthMeters(), runway.version(), runway.requestId(),
                Timestamp.from(runway.createdAt()), Timestamp.from(runway.updatedAt()));
    }

    public Optional<ControlledRunway> findById(String runwayId) {
        List<ControlledRunway> rows = jdbc.query("SELECT * FROM controlled_runway WHERE runway_id = ?",
                MAPPER, runwayId);
        return rows.stream().findFirst();
    }

    /** 行级锁定取受控跑道，供长度修改串行化。 */
    public Optional<ControlledRunway> findByIdForUpdate(String runwayId) {
        List<ControlledRunway> rows = jdbc.query(
                "SELECT * FROM controlled_runway WHERE runway_id = ? FOR UPDATE", MAPPER, runwayId);
        return rows.stream().findFirst();
    }

    public boolean existsById(String runwayId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_runway WHERE runway_id = ?", Integer.class, runwayId);
        return count != null && count > 0;
    }

    /** 全量更新长度并把聚合版本加1。 */
    public void updateLength(String runwayId, int lengthMeters, Instant updatedAt) {
        jdbc.update("UPDATE controlled_runway SET length_meters = ?, version = version + 1, updated_at = ?"
                        + " WHERE runway_id = ?",
                lengthMeters, Timestamp.from(updatedAt), runwayId);
    }
}
