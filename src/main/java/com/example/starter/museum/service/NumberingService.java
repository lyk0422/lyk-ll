package com.example.starter.museum.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 领域单号生成：合同 L-、异常 AL-、工单 WO-，基于 H2 序列，事务内唯一递增。 */
@Service
public class NumberingService {

    private final JdbcTemplate jdbc;

    public NumberingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextContractNo() {
        return "L-" + next();
    }

    public String nextAlertNo() {
        return "AL-" + next();
    }

    public String nextOrderNo() {
        return "WO-" + next();
    }

    private long next() {
        return jdbc.queryForObject("CALL NEXT VALUE FOR domain_seq", Long.class);
    }
}
