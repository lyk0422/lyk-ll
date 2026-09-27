package com.example.starter.cosmetic.port;

import com.example.starter.cosmetic.domain.ChildrenSafetyReport;

import java.util.Optional;

/**
 * 儿童安全报告登记端口（合成报告数据，独立于成人功效评价报告）。
 */
public interface ChildrenReportPort {

    /** 登记或更新儿童安全报告（同编号覆盖为最新合成内容）。 */
    void upsert(ChildrenSafetyReport report);

    /** 按报告编号查询。 */
    Optional<ChildrenSafetyReport> findByNo(String reportNo);
}
