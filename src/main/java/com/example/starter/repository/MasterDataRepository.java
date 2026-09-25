package com.example.starter.repository;

import com.example.starter.domain.BaseStation;
import com.example.starter.domain.Generator;
import com.example.starter.domain.GeneratorStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 基站与油机主数据访问。
 */
@Repository
public class MasterDataRepository {

    private final JdbcTemplate jdbc;

    public MasterDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建基站（合成数据），尚无抄表时当前剩余容量为空。 */
    public void insertStation(BaseStation station) {
        jdbc.update("""
                        INSERT INTO base_station
                            (station_code, station_name, battery_capacity_wh, discharge_rate_w,
                             current_remaining_wh, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                station.stationCode(), station.stationName(), station.capacityWh(),
                station.dischargeRateW(), station.currentRemainingWh(),
                Timestamp.from(station.createdAt()));
    }

    /** 写入最近已知剩余容量（抄表归位后调用）。 */
    public void updateRemainingWh(long stationId, java.math.BigDecimal remainingWh) {
        jdbc.update("UPDATE base_station SET current_remaining_wh = ? WHERE id = ?",
                remainingWh, stationId);
    }

    /** 市电恢复：电池回充至额定容量。 */
    public void rechargeToFull(long stationId, java.math.BigDecimal capacityWh) {
        jdbc.update("UPDATE base_station SET current_remaining_wh = ? WHERE id = ?",
                capacityWh, stationId);
    }

    public Optional<BaseStation> findStationByCode(String stationCode) {
        return jdbc.query("SELECT * FROM base_station WHERE station_code = ?",
                BaseStation.rowMapper(), stationCode).stream().findFirst();
    }

    public Optional<BaseStation> findStationById(long stationId) {
        return jdbc.query("SELECT * FROM base_station WHERE id = ?",
                BaseStation.rowMapper(), stationId).stream().findFirst();
    }

    public BaseStation getStationByCode(String stationCode) {
        return findStationByCode(stationCode).orElseThrow();
    }

    /** 新建油机（合成数据），初始空闲。 */
    public void insertGenerator(Generator generator) {
        jdbc.update("""
                        INSERT INTO generator (gen_code, gen_name, status, current_order_id, created_at)
                        VALUES (?, ?, ?, NULL, ?)
                        """,
                generator.genCode(), generator.genName(), generator.status().name(),
                Timestamp.from(generator.createdAt()));
    }

    public Optional<Generator> findGeneratorByCode(String genCode) {
        return jdbc.query("SELECT * FROM generator WHERE gen_code = ?",
                Generator.rowMapper(), genCode).stream().findFirst();
    }

    public Generator getGeneratorByCode(String genCode) {
        return findGeneratorByCode(genCode).orElseThrow();
    }

    public Optional<Generator> findGeneratorById(Long id) {
        return jdbc.query("SELECT * FROM generator WHERE id = ?",
                Generator.rowMapper(), id).stream().findFirst();
    }

    /**
     * 条件更新：仅当油机仍空闲时占用为在途。并发下唯一成功者返回 true。
     */
    public boolean tryOccupyGenerator(long genId, long orderId) {
        int rows = jdbc.update("""
                        UPDATE generator
                           SET status = ?, current_order_id = ?
                         WHERE id = ? AND status = 'IDLE'
                        """, GeneratorStatus.DISPATCHED.name(), orderId, genId);
        return rows == 1;
    }

    /** 自动选取一台空闲油机（可排除指定油机），按编号顺序取第一台。 */
    public Optional<Generator> findFirstIdleGenerator(Long excludeGenId) {
        return findIdleGenerators(excludeGenId).stream().findFirst();
    }

    /** 列出全部空闲油机（可排除指定油机），按编号顺序，供并发分配时逐台抢坑。 */
    public java.util.List<Generator> findIdleGenerators(Long excludeGenId) {
        if (excludeGenId == null) {
            return jdbc.query("SELECT * FROM generator WHERE status = 'IDLE' ORDER BY id",
                    Generator.rowMapper());
        }
        return jdbc.query("SELECT * FROM generator WHERE status = 'IDLE' AND id <> ? ORDER BY id",
                Generator.rowMapper(), excludeGenId);
    }
}
