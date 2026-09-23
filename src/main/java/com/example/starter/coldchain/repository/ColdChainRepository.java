package com.example.starter.coldchain.repository;

import com.example.starter.coldchain.domain.SegmentStatus;
import com.example.starter.coldchain.entity.HandoverRecord;
import com.example.starter.coldchain.entity.Shipment;
import com.example.starter.coldchain.entity.TemperatureReading;
import com.example.starter.coldchain.entity.TransportSegment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 冷链追溯数据访问：H2（MODE=MySQL），全部使用参数化 SQL。
 */
@Repository
public class ColdChainRepository {

    private final JdbcTemplate jdbc;

    public ColdChainRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Shipment> SHIPMENT_MAPPER = (rs, n) -> new Shipment(
            rs.getString("shipment_id"),
            rs.getDouble("min_temp"),
            rs.getDouble("max_temp"),
            rs.getInt("max_excursion_minutes"),
            rs.getInt("max_sample_interval_seconds"),
            rs.getInt("current_version"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private static final RowMapper<TransportSegment> SEGMENT_MAPPER = (rs, n) -> {
        Timestamp end = rs.getTimestamp("end_time");
        Long lastSeq = rs.getObject("last_sequence", Long.class);
        return new TransportSegment(
                rs.getLong("id"),
                rs.getString("shipment_id"),
                rs.getInt("version"),
                rs.getString("custodian"),
                rs.getTimestamp("start_time").toLocalDateTime(),
                end == null ? null : end.toLocalDateTime(),
                rs.getLong("first_sequence"),
                lastSeq,
                SegmentStatus.valueOf(rs.getString("status"))
        );
    };

    private static final RowMapper<TemperatureReading> READING_MAPPER = (rs, n) -> new TemperatureReading(
            rs.getLong("id"),
            rs.getString("shipment_id"),
            rs.getLong("sequence_no"),
            rs.getString("device_no"),
            rs.getTimestamp("sampled_at").toLocalDateTime(),
            rs.getDouble("temperature")
    );

    private static final RowMapper<HandoverRecord> HANDOVER_MAPPER = (rs, n) -> {
        String status = rs.getString("segment_status");
        Integer newVersion = rs.getObject("new_version", Integer.class);
        return new HandoverRecord(
                rs.getString("request_id"),
                rs.getString("shipment_id"),
                rs.getInt("expected_version"),
                rs.getString("receiver"),
                rs.getTimestamp("handover_at").toLocalDateTime(),
                rs.getLong("last_sequence"),
                rs.getString("fingerprint"),
                status == null ? null : SegmentStatus.valueOf(status),
                newVersion,
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    };

    /** 按主键查货件；不存在返回 {@code null}。 */
    public Shipment findShipment(String shipmentId) {
        List<Shipment> list = jdbc.query(
                "SELECT * FROM shipment WHERE shipment_id = ?", SHIPMENT_MAPPER, shipmentId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 行锁查货件（必须在事务内调用）。 */
    public Shipment lockShipment(String shipmentId) {
        List<Shipment> list = jdbc.query(
                "SELECT * FROM shipment WHERE shipment_id = ? FOR UPDATE", SHIPMENT_MAPPER, shipmentId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 新建货件。 */
    public void insertShipment(Shipment shipment) {
        jdbc.update("INSERT INTO shipment (shipment_id, min_temp, max_temp, max_excursion_minutes,"
                        + " max_sample_interval_seconds, current_version, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                shipment.shipmentId(), shipment.minTemp(), shipment.maxTemp(),
                shipment.maxExcursionMinutes(), shipment.maxSampleIntervalSeconds(),
                shipment.currentVersion(), Timestamp.valueOf(shipment.createdAt()));
    }

    /** 货件当前版本号 CAS 自增；返回受影响行数（0 表示版本已变化）。 */
    public int compareAndIncrementVersion(String shipmentId, int expectedVersion) {
        return jdbc.update("UPDATE shipment SET current_version = current_version + 1"
                        + " WHERE shipment_id = ? AND current_version = ?",
                shipmentId, expectedVersion);
    }

    /** 插入运输段。 */
    public void insertSegment(TransportSegment segment) {
        jdbc.update("INSERT INTO transport_segment (shipment_id, version, custodian, start_time,"
                        + " end_time, first_sequence, last_sequence, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                segment.shipmentId(), segment.version(), segment.custodian(),
                Timestamp.valueOf(segment.startTime()),
                segment.endTime() == null ? null : Timestamp.valueOf(segment.endTime()),
                segment.firstSequence(), segment.lastSequence(), segment.status().name());
    }

    /** 查货件当前开放段；不存在返回 {@code null}。 */
    public TransportSegment findOpenSegment(String shipmentId) {
        List<TransportSegment> list = jdbc.query(
                "SELECT * FROM transport_segment WHERE shipment_id = ? AND status = 'OPEN'",
                SEGMENT_MAPPER, shipmentId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按版本号查段；不存在返回 {@code null}。 */
    public TransportSegment findSegment(String shipmentId, int version) {
        List<TransportSegment> list = jdbc.query(
                "SELECT * FROM transport_segment WHERE shipment_id = ? AND version = ?",
                SEGMENT_MAPPER, shipmentId, version);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 行锁查开放段（必须在事务内调用）；不存在返回 {@code null}。 */
    public TransportSegment lockOpenSegment(String shipmentId) {
        List<TransportSegment> list = jdbc.query(
                "SELECT * FROM transport_segment WHERE shipment_id = ? AND status = 'OPEN' FOR UPDATE",
                SEGMENT_MAPPER, shipmentId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查货件全部运输段（按版本升序）。 */
    public List<TransportSegment> findAllSegments(String shipmentId) {
        return jdbc.query(
                "SELECT * FROM transport_segment WHERE shipment_id = ? ORDER BY version",
                SEGMENT_MAPPER, shipmentId);
    }

    /** 关闭旧段：写入结束时间、末序号与判定状态；仅当段仍开放时生效。 */
    public int closeSegment(String shipmentId, int version, LocalDateTime endTime,
                            long lastSequence, SegmentStatus status) {
        return jdbc.update("UPDATE transport_segment SET end_time = ?, last_sequence = ?,"
                        + " status = ? WHERE shipment_id = ? AND version = ? AND status = 'OPEN'",
                Timestamp.valueOf(endTime), lastSequence, status.name(), shipmentId, version);
    }

    /** 按序号查读数；不存在返回 {@code null}。 */
    public TemperatureReading findReading(String shipmentId, long sequenceNo) {
        List<TemperatureReading> list = jdbc.query(
                "SELECT * FROM temperature_reading WHERE shipment_id = ? AND sequence_no = ?",
                READING_MAPPER, shipmentId, sequenceNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 插入温度读数；序号冲突由唯一约束抛出 {@link DuplicateKeyException}。 */
    public void insertReading(TemperatureReading reading) {
        jdbc.update("INSERT INTO temperature_reading (shipment_id, sequence_no, device_no,"
                        + " sampled_at, temperature) VALUES (?, ?, ?, ?, ?)",
                reading.shipmentId(), reading.sequenceNo(), reading.deviceNo(),
                Timestamp.valueOf(reading.sampledAt()), reading.temperature());
    }

    /** 查询序号闭区间内的读数（按序号升序）。 */
    public List<TemperatureReading> findReadingsBetween(String shipmentId, long firstSeq, long lastSeq) {
        return jdbc.query("SELECT * FROM temperature_reading WHERE shipment_id = ?"
                        + " AND sequence_no BETWEEN ? AND ? ORDER BY sequence_no",
                READING_MAPPER, shipmentId, firstSeq, lastSeq);
    }

    /** 序号严格小于 givenSeq 的最近一条读数；不存在返回 {@code null}。 */
    public TemperatureReading findReadingBefore(String shipmentId, long givenSeq) {
        List<TemperatureReading> list = jdbc.query("SELECT * FROM temperature_reading"
                        + " WHERE shipment_id = ? AND sequence_no < ? ORDER BY sequence_no DESC"
                        + " LIMIT 1",
                READING_MAPPER, shipmentId, givenSeq);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 序号严格大于 givenSeq 的最早一条读数；不存在返回 {@code null}。 */
    public TemperatureReading findReadingAfter(String shipmentId, long givenSeq) {
        List<TemperatureReading> list = jdbc.query("SELECT * FROM temperature_reading"
                        + " WHERE shipment_id = ? AND sequence_no > ? ORDER BY sequence_no ASC"
                        + " LIMIT 1",
                READING_MAPPER, shipmentId, givenSeq);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 全货件最大序号；无读数返回 {@code null}。 */
    public Long findMaxSequence(String shipmentId) {
        List<Long> list = jdbc.query(
                "SELECT MAX(sequence_no) FROM temperature_reading WHERE shipment_id = ?",
                (rs, n) -> rs.getLong(1), shipmentId);
        Long value = list.isEmpty() ? null : list.get(0);
        return (value != null && value == 0L) ? null : value;
    }

    /** 查交接记录；不存在返回 {@code null}。 */
    public HandoverRecord findHandover(String requestId) {
        List<HandoverRecord> list = jdbc.query(
                "SELECT * FROM handover_record WHERE request_id = ?", HANDOVER_MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 插入（冻结）交接记录；requestId 冲突抛出 {@link DuplicateKeyException}。 */
    public void insertHandover(HandoverRecord record) {
        jdbc.update("INSERT INTO handover_record (request_id, shipment_id, expected_version,"
                        + " receiver, handover_at, last_sequence, fingerprint, segment_status,"
                        + " new_version, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.requestId(), record.shipmentId(), record.expectedVersion(),
                record.receiver(), Timestamp.valueOf(record.handoverAt()),
                record.lastSequence(), record.fingerprint(),
                record.segmentStatus() == null ? null : record.segmentStatus().name(),
                record.newVersion(), Timestamp.valueOf(record.createdAt()));
    }

    /** 回填首次交接的判定结果与新段版本号。 */
    public int completeHandover(String requestId, SegmentStatus status, int newVersion) {
        return jdbc.update("UPDATE handover_record SET segment_status = ?, new_version = ?"
                        + " WHERE request_id = ?",
                status.name(), newVersion, requestId);
    }
}
