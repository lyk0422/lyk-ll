package com.example.starter.pv.data;

import java.util.List;

/**
 * 随访/并入信息数据访问。
 */
public interface FollowUpRepository {

    /**
     * 插入一条随访或重复初报并入信息。
     */
    void insert(FollowUpPo po);

    /**
     * 查询个例全部并入信息，按版本升序。
     */
    List<FollowUpPo> findByCaseNo(String caseNo);
}
