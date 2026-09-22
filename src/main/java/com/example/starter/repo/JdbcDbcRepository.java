package com.example.starter.repo;

import com.example.starter.dbc.Endian;
import com.example.starter.dbc.MessageDefinition;
import com.example.starter.dbc.MultiplexMode;
import com.example.starter.dbc.SignalDefinition;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 JdbcTemplate 的 DBC 定义仓库（H2 MODE=MySQL，SQL 同时兼容 MySQL 基本语法）。
 */
@Repository
public class JdbcDbcRepository implements DbcRepository {

    private final JdbcTemplate jdbc;

    public JdbcDbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ImportRequestRow insertRequestIfAbsent(String requestId, String version, String contentHash) {
        try {
            jdbc.update("INSERT INTO dbc_import_requests (request_id, version, content_hash, outcome) "
                    + "VALUES (?, ?, ?, 'SUCCESS')", requestId, version, contentHash);
            return new ImportRequestRow(requestId, version, contentHash, "SUCCESS");
        } catch (DuplicateKeyException ex) {
            return null;
        }
    }

    @Override
    public ImportRequestRow findRequest(String requestId) {
        List<ImportRequestRow> rows = jdbc.query(
                "SELECT request_id, version, content_hash, outcome FROM dbc_import_requests WHERE request_id = ?",
                (rs, rowNum) -> new ImportRequestRow(
                        rs.getString("request_id"), rs.getString("version"),
                        rs.getString("content_hash"), rs.getString("outcome")),
                requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public boolean versionExists(String version) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM dbc_versions WHERE version = ?", Integer.class, version);
        return count != null && count > 0;
    }

    @Override
    public void saveVersion(String version, String requestId, List<MessageDefinition> messages) {
        jdbc.update("INSERT INTO dbc_versions (version, request_id) VALUES (?, ?)", version, requestId);
        for (MessageDefinition message : messages) {
            long messageId = insertMessage(version, message);
            for (SignalDefinition signal : message.signals()) {
                long signalId = insertSignal(messageId, signal);
                signal.enumerations().forEach((raw, label) -> jdbc.update(
                        "INSERT INTO dbc_signal_enums (signal_id, raw_value, label) VALUES (?, ?, ?)",
                        signalId, raw, label));
            }
        }
    }

    private long insertMessage(String version, MessageDefinition message) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO dbc_messages (version, can_id, name, dlc) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, version);
            ps.setLong(2, message.canId());
            ps.setString(3, message.name());
            ps.setInt(4, message.dlc());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new DataIntegrityViolationException("报文主键回填失败: " + message.canId());
        }
        return key.longValue();
    }

    private long insertSignal(long messageId, SignalDefinition signal) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO dbc_signals (message_id, name, start_bit, bit_length, endian, is_signed, "
                            + "factor, offset_value, mux_mode, mux_value) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, messageId);
            ps.setString(2, signal.name());
            ps.setInt(3, signal.startBit());
            ps.setInt(4, signal.length());
            ps.setString(5, signal.endian().name());
            ps.setBoolean(6, signal.signed());
            ps.setDouble(7, signal.factor());
            ps.setDouble(8, signal.offset());
            ps.setString(9, signal.multiplexMode().name());
            if (signal.muxValue() == null) {
                ps.setNull(10, java.sql.Types.BIGINT);
            } else {
                ps.setLong(10, signal.muxValue());
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new DataIntegrityViolationException("信号主键回填失败: " + signal.name());
        }
        return key.longValue();
    }

    @Override
    public Map<Long, MessageDefinition> loadVersion(String version) {
        List<MessageRow> messageRows = jdbc.query(
                "SELECT id, can_id, name, dlc FROM dbc_messages WHERE version = ? ORDER BY id",
                (rs, rowNum) -> new MessageRow(
                        rs.getLong("id"), rs.getLong("can_id"),
                        rs.getString("name"), rs.getInt("dlc")),
                version);
        Map<Long, MessageDefinition> result = new LinkedHashMap<>();
        for (MessageRow messageRow : messageRows) {
            List<SignalDefinition> signals = new ArrayList<>();
            List<SignalRow> signalRows = jdbc.query(
                    "SELECT id, name, start_bit, bit_length, endian, is_signed, factor, offset_value, mux_mode, mux_value "
                            + "FROM dbc_signals WHERE message_id = ? ORDER BY id",
                    (rs, rowNum) -> new SignalRow(
                            rs.getLong("id"), rs.getString("name"), rs.getInt("start_bit"),
                            rs.getInt("bit_length"), Endian.valueOf(rs.getString("endian")),
                            rs.getBoolean("is_signed"), rs.getDouble("factor"), rs.getDouble("offset_value"),
                            MultiplexMode.valueOf(rs.getString("mux_mode")),
                            (Long) rs.getObject("mux_value")),
                    messageRow.id());
            for (SignalRow signalRow : signalRows) {
                Map<Long, String> enums = new LinkedHashMap<>();
                jdbc.query("SELECT raw_value, label FROM dbc_signal_enums WHERE signal_id = ? ORDER BY id",
                        (rs, rowNum) -> {
                            enums.put(rs.getLong("raw_value"), rs.getString("label"));
                            return null;
                        }, signalRow.id());
                signals.add(new SignalDefinition(
                        signalRow.name(), signalRow.startBit(), signalRow.bitLength(),
                        signalRow.endian(), signalRow.signed(), signalRow.factor(), signalRow.offset(),
                        signalRow.muxMode(), signalRow.muxValue(), enums));
            }
            result.put(messageRow.canId(),
                    new MessageDefinition(messageRow.canId(), messageRow.name(), messageRow.dlc(), signals));
        }
        return result;
    }

    private record MessageRow(long id, long canId, String name, int dlc) {
    }

    private record SignalRow(long id, String name, int startBit, int bitLength, Endian endian,
                             boolean signed, double factor, double offset, MultiplexMode muxMode,
                             Long muxValue) {
    }
}
