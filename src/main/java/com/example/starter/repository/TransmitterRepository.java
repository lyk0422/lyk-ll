package com.example.starter.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.TransmitterRow;

/**
 * 发射机台账数据访问。发射机可用频率存于 transmitter_channel。
 */
@Repository
public class TransmitterRepository {

    private final JdbcTemplate jdbc;

    public TransmitterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TransmitterRow> findById(long id) {
        List<TransmitterRow> rows = jdbc.query(
                "SELECT id, code, full_power_watts FROM transmitter WHERE id = ?",
                (rs, n) -> new TransmitterRow(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getInt("full_power_watts")),
                id);
        return rows.stream().findFirst();
    }

    /**
     * 在该频率上可使用（关联）的发射机。
     */
    public List<TransmitterRow> findByFrequency(BigDecimal frequencyMhz) {
        return jdbc.query(
                "SELECT t.id, t.code, t.full_power_watts FROM transmitter t"
                        + " JOIN transmitter_channel tc ON tc.transmitter_id = t.id"
                        + " WHERE tc.frequency_mhz = ? ORDER BY t.id",
                (rs, n) -> new TransmitterRow(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getInt("full_power_watts")),
                frequencyMhz);
    }

    /**
     * 锁定发射机行（SELECT ... FOR UPDATE），用于串行化同一发射机的指令变更。
     */
    public void lockById(long id) {
        jdbc.query("SELECT id FROM transmitter WHERE id = ? FOR UPDATE", (rs, n) -> rs.getLong(1), id);
    }
}
