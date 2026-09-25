package com.example.starter.cosmetic.port;

import com.example.starter.cosmetic.domain.TestReportRecord;

import java.util.Optional;

/**
 * 功效评价试验报告登记端口（合成报告数据）。
 */
public interface ReportPort {

    /** 登记或更新报告（同编号覆盖为最新合成内容）。 */
    void upsert(TestReportRecord report);

    /** 按报告编号查询。 */
    Optional<TestReportRecord> findByNo(String reportNo);
}
