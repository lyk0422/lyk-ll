package com.example.starter.repository.jdbc;

import com.example.starter.api.dto.DbcImportRequest;
import com.example.starter.domain.ByteOrder;
import com.example.starter.domain.DbcVersion;
import com.example.starter.domain.MultiplexRole;
import com.example.starter.domain.VersionDef;
import com.example.starter.repository.DbcDefinitionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DBC 定义存储的 JDBC 实现。
 *
 * <p>{@link #insertVersion} 自身不开启事务，由服务层以事务包裹，保证版本头、
 * 报文、信号、枚举要么全部可见、要么整体回滚。</p>
 */
@Repository
public class JdbcDbcDefinitionRepository implements DbcDefinitionRepository {

    private static final String SELECT_VERSION =
            "SELECT id, version_name, request_id, payload_digest, created_at_nano FROM dbc_version ";

    private static final RowMapper<DbcVersion> VERSION_MAPPER = (rs, rowNum) -> new DbcVersion(
            rs.getLong("id"),
            rs.getString("version_name"),
            rs.getString("request_id"),
            rs.getString("payload_digest"),
            rs.getLong("created_at_nano"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcDbcDefinitionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<DbcVersion> findVersionByName(String versionName) {
        return jdbcTemplate.query(SELECT_VERSION + "WHERE version_name = ?",
                VERSION_MAPPER, versionName).stream().findFirst();
    }

    @Override
    public Optional<DbcVersion> findVersionByRequestId(String requestId) {
        return jdbcTemplate.query(SELECT_VERSION + "WHERE request_id = ?",
                VERSION_MAPPER, requestId).stream().findFirst();
    }

    @Override
    public DbcVersion insertVersion(DbcImportRequest request, String payloadDigest, long createdAtNano) {
        KeyHolder versionKeyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO dbc_version(version_name, request_id, payload_digest, created_at_nano) "
                            + "VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, request.versionName());
            ps.setString(2, request.requestId());
            ps.setString(3, payloadDigest);
            ps.setLong(4, createdAtNano);
            return ps;
        }, versionKeyHolder);
        long versionId = versionKeyHolder.getKey().longValue();

        for (DbcImportRequest.MessageDefDto message : request.messages()) {
            KeyHolder messageKeyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO dbc_message_def(version_id, can_id, message_name, dlc) "
                                + "VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, versionId);
                ps.setLong(2, message.canId());
                ps.setString(3, message.name());
                ps.setInt(4, message.dlc());
                return ps;
            }, messageKeyHolder);
            long messageId = messageKeyHolder.getKey().longValue();

            for (DbcImportRequest.SignalDefDto signal : message.signals()) {
                KeyHolder signalKeyHolder = new GeneratedKeyHolder();
                jdbcTemplate.update(connection -> {
                    PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO dbc_signal_def(message_id, signal_name, byte_order, start_bit, "
                                    + "bit_length, \"SIGNED\", factor, offset_value, unit, multiplex_role, "
                                    + "multiplex_value) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, messageId);
                    ps.setString(2, signal.name());
                    ps.setString(3, signal.byteOrder());
                    ps.setInt(4, signal.startBit());
                    ps.setInt(5, signal.bitLength());
                    ps.setBoolean(6, signal.signed());
                    ps.setDouble(7, signal.factor());
                    ps.setDouble(8, signal.offset());
                    ps.setString(9, signal.unit() == null ? "" : signal.unit());
                    ps.setString(10, signal.multiplexRole());
                    if (signal.multiplexValue() == null) {
                        ps.setNull(11, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(11, signal.multiplexValue());
                    }
                    return ps;
                }, signalKeyHolder);
                long signalId = signalKeyHolder.getKey().longValue();

                if (signal.valueTable() != null) {
                    for (Map.Entry<Long, String> entry : signal.valueTable().entrySet()) {
                        jdbcTemplate.update(
                                "INSERT INTO dbc_signal_enum(signal_id, enum_value, enum_text) VALUES (?, ?, ?)",
                                signalId, entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        return new DbcVersion(versionId, request.versionName(), request.requestId(),
                payloadDigest, createdAtNano);
    }

    @Override
    public Optional<VersionDef> loadVersionDef(String versionName) {
        Optional<DbcVersion> version = findVersionByName(versionName);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        List<VersionDef.MessageDef> messages = new ArrayList<>();
        List<Long> messageIds = jdbcTemplate.queryForList(
                "SELECT id FROM dbc_message_def WHERE version_id = ? ORDER BY id",
                Long.class, version.get().id());
        for (Long messageId : messageIds) {
            messages.add(loadMessage(messageId));
        }
        return Optional.of(new VersionDef(version.get(), List.copyOf(messages)));
    }

    private VersionDef.MessageDef loadMessage(long messageId) {
        var header = jdbcTemplate.queryForMap(
                "SELECT can_id, message_name, dlc FROM dbc_message_def WHERE id = ?", messageId);
        long canId = ((Number) header.get("can_id")).longValue();
        String name = (String) header.get("message_name");
        int dlc = ((Number) header.get("dlc")).intValue();

        List<VersionDef.SignalDef> signals = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT id, signal_name, byte_order, start_bit, bit_length, \"SIGNED\", factor, "
                        + "offset_value, unit, multiplex_role, multiplex_value "
                        + "FROM dbc_signal_def WHERE message_id = ? ORDER BY id",
                rs -> {
                    long signalId = rs.getLong("id");
                    Map<Long, String> valueTable = new LinkedHashMap<>();
                    jdbcTemplate.query(
                            "SELECT enum_value, enum_text FROM dbc_signal_enum WHERE signal_id = ? ORDER BY id",
                            (RowCallbackHandler) enumRs ->
                                    valueTable.put(enumRs.getLong("enum_value"), enumRs.getString("enum_text")),
                            signalId);
                    signals.add(new VersionDef.SignalDef(
                            signalId,
                            rs.getString("signal_name"),
                            ByteOrder.valueOf(rs.getString("byte_order")),
                            rs.getInt("start_bit"),
                            rs.getInt("bit_length"),
                            rs.getBoolean("SIGNED"),
                            rs.getDouble("factor"),
                            rs.getDouble("offset_value"),
                            rs.getString("unit"),
                            MultiplexRole.valueOf(rs.getString("multiplex_role")),
                            (Long) rs.getObject("multiplex_value"),
                            Map.copyOf(valueTable)));
                },
                messageId);

        return new VersionDef.MessageDef(messageId, canId, name, dlc, List.copyOf(signals));
    }

    @Override
    public void lockMessageDef(long messageId) {
        jdbcTemplate.queryForList("SELECT id FROM dbc_message_def WHERE id = ? FOR UPDATE",
                Long.class, messageId);
    }
}
