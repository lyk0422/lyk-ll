package com.example.starter.museum.repo;

import com.example.starter.museum.domain.po.LoanEventPo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.List;

/** 领域事件写入与复算查询。 */
@Repository
public class EventRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public EventRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(Long contractId, Long showcaseId, String type, String detail) {
        jdbc.update(
                "INSERT INTO loan_event (contract_id, showcase_id, type, detail, created_at) VALUES (?, ?, ?, ?, ?)",
                contractId, showcaseId, type, detail, Sql.ts(clock.instant()));
    }

    public List<LoanEventPo> findByContract(Long contractId) {
        return jdbc.query(
                "SELECT * FROM loan_event WHERE contract_id = ? ORDER BY id",
                RowMappers.EVENT, contractId);
    }

    public List<LoanEventPo> findByShowcase(Long showcaseId) {
        return jdbc.query(
                "SELECT * FROM loan_event WHERE showcase_id = ? ORDER BY id",
                RowMappers.EVENT, showcaseId);
    }
}
