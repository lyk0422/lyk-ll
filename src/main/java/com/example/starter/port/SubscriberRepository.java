package com.example.starter.port;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 号码用户状态数据访问；资格校验时以行锁保证并发申请的原子性。
 */
@Repository
public class SubscriberRepository {

    private final JdbcTemplate jdbc;

    public SubscriberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按号码查询用户状态。
     */
    public Optional<SubscriberInfo> findByMsisdn(String msisdn) {
        return jdbc.query(
                        "SELECT msisdn, current_operator, arrears_amount, contract_end_date, last_port_time"
                                + " FROM subscriber WHERE msisdn = ?",
                        (rs, rowNum) -> map(rs), msisdn)
                .stream().findFirst();
    }

    /**
     * 按号码查询并锁定用户行（SELECT ... FOR UPDATE），须在事务内调用；
     * 用于串行化同一号码的并发申请，保证资格校验与占位原子完成。
     */
    public Optional<SubscriberInfo> lockByMsisdn(String msisdn) {
        return jdbc.query(
                        "SELECT msisdn, current_operator, arrears_amount, contract_end_date, last_port_time"
                                + " FROM subscriber WHERE msisdn = ? FOR UPDATE",
                        (rs, rowNum) -> map(rs), msisdn)
                .stream().findFirst();
    }

    private SubscriberInfo map(ResultSet rs) throws SQLException {
        var lastPort = rs.getObject("last_port_time", LocalDateTime.class);
        var contractEnd = rs.getObject("contract_end_date", java.sql.Date.class);
        return new SubscriberInfo(
                rs.getString("msisdn"),
                rs.getString("current_operator"),
                rs.getBigDecimal("arrears_amount"),
                contractEnd == null ? null : contractEnd.toLocalDate(),
                lastPort == null ? null : lastPort.toInstant(ZoneOffset.UTC));
    }
}
