package com.example.starter.cosmetic.child.port;

import com.example.starter.cosmetic.child.domain.ChildSafetyReport;

import java.util.Optional;

/**
 * 儿童安全评价报告登记端口（合成报告；区别于成人功效评价试验报告）。
 */
public interface ChildSafetyReportPort {

    /** 登记或更新儿童安全报告（同编号覆盖为最新合成内容）。 */
    void upsert(ChildSafetyReport report);

    /** 按报告编号查询。 */
    Optional<ChildSafetyReport> findByNo(String reportNo);
}
