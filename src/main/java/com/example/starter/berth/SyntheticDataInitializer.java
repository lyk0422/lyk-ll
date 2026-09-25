package com.example.starter.berth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 合成数据初始化：泊位、引航员、审批锁行，以及 2020-01-01 至 2030-12-31
 * 每日两段允许靠泊时段（UTC 06:00-12:00、13:00-22:00，端点含边界）。
 * 全部数据为合成数据，仅在 JVM 生命周期内保留；重复启动时按行数守卫避免重复写入。
 */
@Component
public class SyntheticDataInitializer implements ApplicationRunner {

    private static final LocalDate FIRST_DAY = LocalDate.of(2020, 1, 1);
    private static final LocalDate LAST_DAY = LocalDate.of(2030, 12, 31);

    private final JdbcTemplate jdbc;

    public SyntheticDataInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedBerths();
        seedPilots();
        seedApprovalLock();
        seedAllowedWindows();
    }

    private void seedBerths() {
        if (count("berth") > 0) {
            return;
        }
        jdbc.update("INSERT INTO berth (id, name, max_draft_m) VALUES (?,?,?)", "BERTH-1", "一号泊位", 12.00);
        jdbc.update("INSERT INTO berth (id, name, max_draft_m) VALUES (?,?,?)", "BERTH-2", "二号泊位", 8.00);
        jdbc.update("INSERT INTO berth (id, name, max_draft_m) VALUES (?,?,?)", "BERTH-3", "三号泊位", 15.00);
    }

    private void seedPilots() {
        if (count("pilot") > 0) {
            return;
        }
        jdbc.update("INSERT INTO pilot (id, name) VALUES (?,?)", "PILOT-1", "引航员甲");
        jdbc.update("INSERT INTO pilot (id, name) VALUES (?,?)", "PILOT-2", "引航员乙");
        jdbc.update("INSERT INTO pilot (id, name) VALUES (?,?)", "PILOT-3", "引航员丙");
    }

    private void seedApprovalLock() {
        if (count("approval_lock") > 0) {
            return;
        }
        jdbc.update("INSERT INTO approval_lock (id) VALUES (1)");
    }

    private void seedAllowedWindows() {
        if (count("allowed_window") > 0) {
            return;
        }
        List<Object[]> batch = new ArrayList<>();
        for (LocalDate day = FIRST_DAY; !day.isAfter(LAST_DAY); day = day.plusDays(1)) {
            batch.add(new Object[]{at(day, 6), at(day, 12)});
            batch.add(new Object[]{at(day, 13), at(day, 22)});
        }
        jdbc.batchUpdate("INSERT INTO allowed_window (start_utc, end_utc) VALUES (?,?)", batch,
                500, (ps, row) -> {
                    ps.setTimestamp(1, (Timestamp) row[0]);
                    ps.setTimestamp(2, (Timestamp) row[1]);
                });
    }

    private static Timestamp at(LocalDate day, int hourUtc) {
        Instant instant = day.atTime(hourUtc, 0).toInstant(ZoneOffset.UTC);
        return Timestamp.from(instant);
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
