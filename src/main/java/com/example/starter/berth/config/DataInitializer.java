package com.example.starter.berth.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用启动时自动建表并写入合成基础数据（泊位、引航员、每日允许靠泊时段）。
 * 建表与种子数据均幂等：表已存在则跳过建表，已有数据则不重复播种。
 * 所有时刻均为 UTC；数据仅保存在 JVM 生命周期的 H2 内存库中。
 */
@Component
public class DataInitializer implements ApplicationRunner {

    /** 合成允许时段的覆盖起始日期（含）。 */
    private static final LocalDate SEED_FROM = LocalDate.of(2026, 9, 1);
    /** 合成允许时段的覆盖结束日期（含）。 */
    private static final LocalDate SEED_TO = LocalDate.of(2026, 12, 31);

    private final JdbcTemplate jdbc;

    public DataInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        createTables();
        seedBerths();
        seedPilots();
        seedAllowedWindows();
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS berth (
                  berth_code VARCHAR(32) PRIMARY KEY COMMENT '泊位代码',
                  max_depth_m DECIMAL(5,2) NOT NULL COMMENT '泊位限深（米），申请吃水超过该值直接拒绝且不产生记录'
                ) COMMENT='泊位基础信息（合成数据）'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS pilot (
                  pilot_code VARCHAR(32) PRIMARY KEY COMMENT '引航员代码',
                  pilot_name VARCHAR(64) NOT NULL COMMENT '引航员姓名（合成数据）'
                ) COMMENT='引航员（合成数据）'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS allowed_window (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
                  berth_code VARCHAR(32) NOT NULL COMMENT '泊位代码',
                  start_at TIMESTAMP NOT NULL COMMENT '允许靠泊时段开始（UTC，含边界）',
                  end_at TIMESTAMP NOT NULL COMMENT '允许靠泊时段结束（UTC，含边界：申请结束时刻可等于该值）'
                ) COMMENT='每日允许靠泊时段（合成潮汐数据）'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS berth_window (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
                  request_id VARCHAR(64) NOT NULL COMMENT '幂等请求键，由申请人提供',
                  fingerprint VARCHAR(64) NOT NULL COMMENT '请求指纹：requestId 与全部业务字段经单元分隔符拼接后的 SHA-256',
                  vessel_name VARCHAR(128) NOT NULL COMMENT '船名（合成数据）',
                  berth_code VARCHAR(32) NOT NULL COMMENT '泊位代码',
                  start_at TIMESTAMP NOT NULL COMMENT '预计靠泊开始时刻（UTC），半开区间起点，含',
                  end_at TIMESTAMP NOT NULL COMMENT '预计靠泊结束时刻（UTC），半开区间终点，不含',
                  draft_m DECIMAL(5,2) NOT NULL COMMENT '船舶吃水（米）',
                  pilots_required INT NOT NULL COMMENT '所需引航员人数',
                  status VARCHAR(16) NOT NULL COMMENT '状态：PENDING 待审批/APPROVED 已批准/ACTIVE 已生效/COMPLETED 已完成/REJECTED 已驳回/CANCELLED 已取消/SUPERSEDED 已被改期取代',
                  supersedes_id BIGINT NULL COMMENT '改期时被取代的原窗口 id；NULL 表示非改期申请',
                  created_at TIMESTAMP NOT NULL COMMENT '创建时间（UTC）',
                  updated_at TIMESTAMP NOT NULL COMMENT '最后更新时间（UTC）',
                  CONSTRAINT uk_berth_window_request UNIQUE (request_id)
                ) COMMENT='靠泊窗口申请单'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS pilot_assignment (
                  window_id BIGINT NOT NULL COMMENT '靠泊窗口 id',
                  pilot_code VARCHAR(32) NOT NULL COMMENT '引航员代码',
                  PRIMARY KEY (window_id, pilot_code)
                ) COMMENT='窗口-引航员分配；窗口进入终态时删除对应行以释放引航员'
                """);
    }

    private void seedBerths() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM berth", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO berth (berth_code, max_depth_m) VALUES (?, ?)",
                List.of(
                        new Object[]{"BERTH-A", 12.00},
                        new Object[]{"BERTH-B", 8.00},
                        new Object[]{"BERTH-C", 15.00}));
    }

    private void seedPilots() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pilot", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            rows.add(new Object[]{String.format("PILOT-%02d", i), "合成引航员" + i});
        }
        jdbc.batchUpdate("INSERT INTO pilot (pilot_code, pilot_name) VALUES (?, ?)", rows);
    }

    /**
     * 为每个泊位的每一天播种两段允许靠泊时段（UTC）：06:00-12:00 与 13:00-20:00。
     */
    private void seedAllowedWindows() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM allowed_window", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        List<Object[]> rows = new ArrayList<>();
        for (String berth : List.of("BERTH-A", "BERTH-B", "BERTH-C")) {
            for (LocalDate day = SEED_FROM; !day.isAfter(SEED_TO); day = day.plusDays(1)) {
                rows.add(windowRow(berth, day, LocalTime.of(6, 0), LocalTime.of(12, 0)));
                rows.add(windowRow(berth, day, LocalTime.of(13, 0), LocalTime.of(20, 0)));
            }
        }
        jdbc.batchUpdate("INSERT INTO allowed_window (berth_code, start_at, end_at) VALUES (?, ?, ?)", rows);
    }

    private Object[] windowRow(String berth, LocalDate day, LocalTime from, LocalTime to) {
        return new Object[]{
                berth,
                Timestamp.from(day.atTime(from).toInstant(ZoneOffset.UTC)),
                Timestamp.from(day.atTime(to).toInstant(ZoneOffset.UTC))};
    }
}
