package com.example.starter.data;

import com.example.starter.domain.CustodySegment;
import com.example.starter.domain.HandoverRecord;
import com.example.starter.domain.SegmentStatus;
import com.example.starter.domain.Shipment;
import com.example.starter.domain.TemperatureReading;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 冷链追溯数据访问层（H2，MODE=MySQL）。
 *
 * <p>所有方法只做单表原子读写；跨表一致性由服务层事务与 shipment 行锁（无操作 UPDATE）保证。</p>
 */
@Repository
public class ColdChainRepository {

    private final JdbcTemplate jdbcTemplate;

    public ColdChainRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------ 货件

    /** 插入货件；主键冲突抛出 {@link DuplicateKeyException}。 */
    public void insertShipment(Shipment shipment) {
        jdbcTemplate.update("""
                INSERT INTO shipment
                    (shipment_id, min_temp_c, max_temp_c, max_excursion_minutes,
                     max_sample_interval_minutes, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                shipment.shipmentId(),
                shipment.minTempC(),
                shipment.maxTempC(),
                shipment.maxExcursionMinutes(),
                shipment.maxSampleIntervalMinutes(),
                shipment.version(),
                shipment.createdAt());
    }

    /** 按主键查询货件，不存在返回 null。 */
    public Shipment findShipment(String shipmentId) {
        List<Shipment> list = jdbcTemplate.query(
                "SELECT shipment_id, min_temp_c, max_temp_c, max_excursion_minutes, "
                        + "max_sample_interval_minutes, version, created_at "
                        + "FROM shipment WHERE shipment_id = ?",
                SHIPMENT_MAPPER, shipmentId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 通过无操作 UPDATE 获取货件行写锁（比 SELECT ... FOR UPDATE 在 H2 上更可靠地串行化并发事务），
     * 货件不存在返回 false。调用方须在事务内随后用 {@link #findShipment} 读取。
     */
    public boolean lockShipment(String shipmentId) {
        return jdbcTemplate.update(
                "UPDATE shipment SET version = version WHERE shipment_id = ?", shipmentId) > 0;
    }

    /** 在已持有行锁时把货件版本号加一。 */
    public void incrementVersion(String shipmentId) {
        jdbcTemplate.update("UPDATE shipment SET version = version + 1 WHERE shipment_id = ?",
                shipmentId);
    }

    // ------------------------------------------------------------------ 运输段

    /** 插入新打开段（首末序号允许为 null）。 */
    public void insertSegment(CustodySegment segment) {
        jdbcTemplate.update("""
                INSERT INTO custody_segment
                    (shipment_id, version, custodian, start_time, end_time,
                     first_sequence, last_sequence, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                segment.shipmentId(),
                segment.version(),
                segment.custodian(),
                segment.startTime(),
                segment.endTime(),
                segment.firstSequence(),
                segment.lastSequence(),
                segment.status().name());
    }

    /** 查询货件当前打开段（status=OPEN），不存在返回 null。 */
    public CustodySegment findOpenSegment(String shipmentId) {
        List<CustodySegment> list = jdbcTemplate.query(
                "SELECT id, shipment_id, version, custodian, start_time, end_time, "
                        + "first_sequence, last_sequence, status "
                        + "FROM custody_segment WHERE shipment_id = ? AND status = 'OPEN'",
                SEGMENT_MAPPER, shipmentId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询货件全部运输段（按 version 升序）。 */
    public List<CustodySegment> findSegments(String shipmentId) {
        return jdbcTemplate.query(
                "SELECT id, shipment_id, version, custodian, start_time, end_time, "
                        + "first_sequence, last_sequence, status "
                        + "FROM custody_segment WHERE shipment_id = ? ORDER BY version ASC",
                SEGMENT_MAPPER, shipmentId);
    }

    /** 关闭段：写入结束时间、末序号与判定状态。 */
    public void closeSegment(long id, long endTime, long lastSequence, SegmentStatus status) {
        jdbcTemplate.update("""
                        UPDATE custody_segment
                           SET end_time = ?, last_sequence = ?, status = ?
                         WHERE id = ? AND status = 'OPEN'
                        """,
                endTime, lastSequence, status.name(), id);
    }

    /** 收到更小序号读数时下探首序号（乱序到达时首序号取段内最小已收序号）。 */
    public void updateFirstSequence(long id, long firstSequence) {
        jdbcTemplate.update(
                "UPDATE custody_segment SET first_sequence = ? WHERE id = ?",
                firstSequence, id);
    }

    /** 收到更大序号读数时推进末序号。 */
    public void updateLastSequence(long id, long lastSequence) {
        jdbcTemplate.update(
                "UPDATE custody_segment SET last_sequence = ? WHERE id = ?",
                lastSequence, id);
    }

    // ------------------------------------------------------------------ 读数

    /** 插入读数；主键冲突由 {@link DuplicateKeyException} 承载。 */
    public void insertReading(TemperatureReading reading) {
        jdbcTemplate.update("""
                INSERT INTO temperature_reading
                    (shipment_id, sequence_no, sampled_at, temperature_c, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                reading.shipmentId(),
                reading.sequenceNo(),
                reading.sampledAt(),
                reading.temperatureC(),
                reading.createdAt());
    }

    /** 按序号查询读数，不存在返回 null。 */
    public TemperatureReading findReading(String shipmentId, long sequenceNo) {
        List<TemperatureReading> list = jdbcTemplate.query(
                "SELECT shipment_id, sequence_no, sampled_at, temperature_c, created_at "
                        + "FROM temperature_reading WHERE shipment_id = ? AND sequence_no = ?",
                READING_MAPPER, shipmentId, sequenceNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询序号闭区间 [from, to] 内的全部读数（按序号升序）。 */
    public List<TemperatureReading> findReadingsBetween(String shipmentId, long from, long to) {
        return jdbcTemplate.query(
                "SELECT shipment_id, sequence_no, sampled_at, temperature_c, created_at "
                        + "FROM temperature_reading "
                        + "WHERE shipment_id = ? AND sequence_no BETWEEN ? AND ? "
                        + "ORDER BY sequence_no ASC",
                READING_MAPPER, shipmentId, from, to);
    }

    /** 查询序号严格大于 after 的全部读数（按序号升序）。 */
    public List<TemperatureReading> findReadingsAfter(String shipmentId, long after) {
        return jdbcTemplate.query(
                "SELECT shipment_id, sequence_no, sampled_at, temperature_c, created_at "
                        + "FROM temperature_reading WHERE shipment_id = ? AND sequence_no > ? "
                        + "ORDER BY sequence_no ASC",
                READING_MAPPER, shipmentId, after);
    }

    // ------------------------------------------------------------------ 交接

    /** 插入交接冻结记录；主键冲突由 {@link DuplicateKeyException} 承载。 */
    public void insertHandover(HandoverRecord record) {
        jdbcTemplate.update("""
                INSERT INTO handover_record
                    (shipment_id, request_id, fingerprint, custodian, expected_version,
                     receiver, handover_at, last_sequence, result_status, closed_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.shipmentId(),
                record.requestId(),
                record.fingerprint(),
                record.custodian(),
                record.expectedVersion(),
                record.receiver(),
                record.handoverAt(),
                record.lastSequence(),
                record.resultStatus().name(),
                record.closedVersion(),
                record.createdAt());
    }

    /** 按幂等键查询冻结记录，不存在返回 null。 */
    public HandoverRecord findHandover(String shipmentId, String requestId) {
        List<HandoverRecord> list = jdbcTemplate.query(
                "SELECT id, shipment_id, request_id, fingerprint, custodian, expected_version, "
                        + "receiver, handover_at, last_sequence, result_status, closed_version, created_at "
                        + "FROM handover_record WHERE shipment_id = ? AND request_id = ?",
                HANDOVER_MAPPER, shipmentId, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 测试与场景间清理用：清空全部业务表。 */
    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM handover_record");
        jdbcTemplate.update("DELETE FROM temperature_reading");
        jdbcTemplate.update("DELETE FROM custody_segment");
        jdbcTemplate.update("DELETE FROM shipment");
    }

    // ------------------------------------------------------------------ 映射

    private static final RowMapper<Shipment> SHIPMENT_MAPPER = new RowMapper<>() {
        @Override
        public Shipment mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Shipment(
                    rs.getString("shipment_id"),
                    rs.getBigDecimal("min_temp_c"),
                    rs.getBigDecimal("max_temp_c"),
                    rs.getInt("max_excursion_minutes"),
                    rs.getInt("max_sample_interval_minutes"),
                    rs.getInt("version"),
                    rs.getLong("created_at"));
        }
    };

    private static final RowMapper<CustodySegment> SEGMENT_MAPPER = new RowMapper<>() {
        @Override
        public CustodySegment mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CustodySegment(
                    rs.getLong("id"),
                    rs.getString("shipment_id"),
                    rs.getInt("version"),
                    rs.getString("custodian"),
                    rs.getLong("start_time"),
                    (Long) rs.getObject("end_time"),
                    (Long) rs.getObject("first_sequence"),
                    (Long) rs.getObject("last_sequence"),
                    SegmentStatus.valueOf(rs.getString("status")));
        }
    };

    private static final RowMapper<TemperatureReading> READING_MAPPER = new RowMapper<>() {
        @Override
        public TemperatureReading mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TemperatureReading(
                    rs.getString("shipment_id"),
                    rs.getLong("sequence_no"),
                    rs.getLong("sampled_at"),
                    rs.getBigDecimal("temperature_c"),
                    rs.getLong("created_at"));
        }
    };

    private static final RowMapper<HandoverRecord> HANDOVER_MAPPER = new RowMapper<>() {
        @Override
        public HandoverRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new HandoverRecord(
                    rs.getLong("id"),
                    rs.getString("shipment_id"),
                    rs.getString("request_id"),
                    rs.getString("fingerprint"),
                    rs.getString("custodian"),
                    rs.getInt("expected_version"),
                    rs.getString("receiver"),
                    rs.getLong("handover_at"),
                    rs.getLong("last_sequence"),
                    SegmentStatus.valueOf(rs.getString("result_status")),
                    rs.getInt("closed_version"),
                    rs.getLong("created_at"));
        }
    };
}
