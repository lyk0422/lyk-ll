package com.example.starter.service;

import com.example.starter.api.dto.ActionResult;
import com.example.starter.api.dto.CardView;
import com.example.starter.api.dto.CoverageRequest;
import com.example.starter.api.dto.DispatchRequest;
import com.example.starter.api.dto.InspectionRequest;
import com.example.starter.api.dto.ReleaseRequest;
import com.example.starter.api.dto.ToolRegisterRequest;
import com.example.starter.api.dto.TorqueRequest;
import com.example.starter.api.dto.TraceView;
import com.example.starter.api.dto.WitnessRequest;

/**
 * 航空装配力矩紧固与见证点控制业务服务。
 *
 * <p>所有携带 requestId 的变更操作返回 {@link Outcome}：首次结果（含失败结果）落幂等台账，
 * 同键同参重放原样返回；同键异参由幂等执行器直接拒绝为 409，业务动作不执行。
 */
public interface OperationService {

    /** 派工：创建工序卡与紧固点工艺，占用工位。 */
    Outcome dispatch(DispatchRequest request);

    /** 力矩紧固测量；归档后的迟到测量仅登记迟到事件。 */
    Outcome measure(TorqueRequest request);

    /** 自检互检签署，签署人必须不同。 */
    Outcome inspect(InspectionRequest request);

    /** H 点见证：到场或缺席（转等待）。 */
    Outcome witness(WitnessRequest request);

    /** 按当前时间检查等待是否满 2 小时（含恰满），满则升级。 */
    Outcome checkEscalation(String cardNo);

    /** 进入覆盖工序：H 点未见证（含等待/升级）一律拒绝。 */
    Outcome coverage(CoverageRequest request);

    /** 放行归档（终态）；重复归档幂等成功。 */
    Outcome release(ReleaseRequest request);

    /** 查询工序卡状态。 */
    CardView getCard(String cardNo);

    /** 复算力矩曲线、见证链与返工台账。 */
    TraceView trace(String cardNo);

    /** 登记力矩工具及其日校验日期（合成数据），同编号以最新登记为准。 */
    ActionResult registerTool(ToolRegisterRequest request);
}
