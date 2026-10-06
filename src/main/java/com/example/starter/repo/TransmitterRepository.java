package com.example.starter.repo;

import java.math.BigDecimal;
import java.util.Optional;

import com.example.starter.domain.Transmitter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 发射机台账与合成保护表的数据访问。
 */
@Repository
public class TransmitterRepository {

    private static final RowMapper<Transmitter> MAPPER = (rs, n) -> new Transmitter(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getInt("full_power_w"));

    private final JdbcTemplate jdbc;

    public TransmitterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Transmitter> findById(long id) {
        return jdbc.query("SELECT * FROM transmitter WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * 在当前事务内锁定发射机台账行，串行化该发射机的指令下达/变更，避免并发下出现多条执行中指令。
     */
    public void lockById(long id) {
        jdbc.queryForObject("SELECT id FROM transmitter WHERE id = ? FOR UPDATE", Long.class, id);
    }

    /**
     * 按频率经频道映射定位所属发射机。
     *
     * @param frequencyMhz 频率，单位 MHz
     */
    public Optional<Transmitter> findByFrequency(BigDecimal frequencyMhz) {
        return jdbc.query("SELECT t.* FROM transmitter t"
                        + " JOIN transmitter_channel c ON c.transmitter_id = t.id"
                        + " WHERE c.frequency_mhz = ?", MAPPER, frequencyMhz)
                .stream().findFirst();
    }

    /**
     * 查询合成保护表给出的频率限值。
     *
     * @param frequencyMhz 频率，单位 MHz
     * @return 限值（W）；保护表无该频率时为空
     */
    public Optional<Integer> findProtectionLimit(BigDecimal frequencyMhz) {
        return jdbc.query("SELECT limit_w FROM protection_limit WHERE frequency_mhz = ?",
                (rs, n) -> rs.getInt("limit_w"), frequencyMhz).stream().findFirst();
    }
}
