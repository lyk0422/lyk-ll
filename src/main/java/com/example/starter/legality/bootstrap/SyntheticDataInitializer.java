package com.example.starter.legality.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时写入合成基础数据（机场时区、航班、机组成员），仅在表为空时执行。
 * 数据仅在 JVM 生命周期内保留（H2 内存库）。
 */
@Component
@Order(0)
public class SyntheticDataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public SyntheticDataInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer airportCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM airport", Integer.class);
        if (airportCount != null && airportCount > 0) {
            return;
        }

        jdbcTemplate.update(
                "INSERT INTO airport(code, time_zone_id, display_name) VALUES (?, ?, ?)",
                "UTC", "UTC", "合成协调世界时机场");
        jdbcTemplate.update(
                "INSERT INTO airport(code, time_zone_id, display_name) VALUES (?, ?, ?)",
                "PVG", "Asia/Shanghai", "合成上海浦东机场");
        jdbcTemplate.update(
                "INSERT INTO airport(code, time_zone_id, display_name) VALUES (?, ?, ?)",
                "FRA", "Europe/Berlin", "合成法兰克福机场");

        jdbcTemplate.update(
                "INSERT INTO flight(flight_no, departure_airport_code, arrival_airport_code) VALUES (?, ?, ?)",
                "CA101", "PVG", "FRA");
        jdbcTemplate.update(
                "INSERT INTO flight(flight_no, departure_airport_code, arrival_airport_code) VALUES (?, ?, ?)",
                "CA102", "FRA", "PVG");
        jdbcTemplate.update(
                "INSERT INTO flight(flight_no, departure_airport_code, arrival_airport_code) VALUES (?, ?, ?)",
                "CA200", "PVG", "UTC");
        jdbcTemplate.update(
                "INSERT INTO flight(flight_no, departure_airport_code, arrival_airport_code) VALUES (?, ?, ?)",
                "UT001", "UTC", "PVG");

        jdbcTemplate.update(
                "INSERT INTO crew_member(member_code, name, base_airport_code) VALUES (?, ?, ?)",
                "C001", "合成张三", "PVG");
        jdbcTemplate.update(
                "INSERT INTO crew_member(member_code, name, base_airport_code) VALUES (?, ?, ?)",
                "C002", "合成李四", "PVG");
        jdbcTemplate.update(
                "INSERT INTO crew_member(member_code, name, base_airport_code) VALUES (?, ?, ?)",
                "C003", "合成王五", "FRA");
    }
}
