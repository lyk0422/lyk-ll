package com.example.starter.cosmetic.port;

import com.example.starter.cosmetic.domain.TestReportRecord;
import com.example.starter.cosmetic.domain.ChildSafetyReportRecord;

import java.util.Optional;

/**
 * 报告登记端口（合成报告数据）：成人功效评价试验报告与儿童安全报告。
 */
public interface ReportPort {

    /** 登记或更新报告（同编号覆盖为最新合成内容）。 */
    void upsert(TestReportRecord report);

    /** 按报告编号查询。 */
    Optional<TestReportRecord> findByNo(String reportNo);

    /** 登记或更新儿童安全报告（同编号覆盖为最新合成内容）。 */
    void upsertChildSafetyReport(ChildSafetyReportRecord report);

    /** 按报告编号查询儿童安全报告。 */
    Optional<ChildSafetyReportRecord> findChildSafetyReport(String reportNo);
}
