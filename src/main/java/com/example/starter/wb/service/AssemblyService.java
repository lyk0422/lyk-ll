package com.example.starter.wb.service;

import com.example.starter.wb.domain.UldOccupationRow;
import com.example.starter.wb.dto.AssemblyPlanResponse;
import com.example.starter.wb.dto.AssemblySubmitRequest;

import java.util.List;

/**
 * 集装器装配图业务边界：装配提交（版本联动）、查询与实物占用追溯。
 */
public interface AssemblyService {

    /** 提交装配图新版本：匹配舱单与装配图版本，原子写图、更新货运力矩并使旧复核失效。 */
    AssemblyPlanResponse submit(long flightId, AssemblySubmitRequest request);

    /** 航班当前有效装配图；无有效图返回 404。 */
    AssemblyPlanResponse getActive(long flightId);

    /** 航班全部装配图版本（含作废/已释放），按版本号升序，只读追溯。 */
    List<AssemblyPlanResponse> listVersions(long flightId);

    /** 集装器实物占用查询；未被占用返回空。 */
    java.util.Optional<UldOccupationRow> getOccupation(String uldNumber);
}
