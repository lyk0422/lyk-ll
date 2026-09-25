package com.example.starter.survey.data;

import com.example.starter.survey.domain.Ship;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 船舶表数据访问。 */
@Repository
public class ShipRepository {

    private final JdbcTemplate jdbc;

    public ShipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Ship> MAPPER = (rs, n) -> new Ship(
            rs.getLong("id"),
            rs.getString("imo"),
            rs.getString("name"),
            rs.getDate("annual_anniversary").toLocalDate(),
            rs.getDate("delivery_date").toLocalDate()
    );

    /** 插入合成船舶。 */
    public Ship insert(String imo, String name,
                       java.time.LocalDate annualAnniversary,
                       java.time.LocalDate deliveryDate,
                       Instant now) {
        jdbc.update("""
                        INSERT INTO ship (imo, name, annual_anniversary, delivery_date, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                imo, name,
                Date.valueOf(annualAnniversary),
                Date.valueOf(deliveryDate),
                Timestamp.from(now));
        return findByImo(imo).orElseThrow();
    }

    /** 按 IMO 查找。 */
    public Optional<Ship> findByImo(String imo) {
        return jdbc.query("SELECT * FROM ship WHERE imo = ?", MAPPER, imo)
                .stream().findFirst();
    }

    /** 按主键查找。 */
    public Optional<Ship> findById(long id) {
        return jdbc.query("SELECT * FROM ship WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 列出全部船舶（合成数据自检用）。 */
    public List<Ship> findAll() {
        return jdbc.query("SELECT * FROM ship ORDER BY id", MAPPER);
    }
}
