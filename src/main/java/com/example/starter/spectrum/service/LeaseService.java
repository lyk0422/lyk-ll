package com.example.starter.spectrum.service;

import com.example.starter.spectrum.api.CancelLeaseRequest;
import com.example.starter.spectrum.api.CreateLeaseRequest;
import com.example.starter.spectrum.api.LeaseResponse;
import com.example.starter.spectrum.api.ModifyLeaseRequest;
import com.example.starter.spectrum.ConflictDetail;
import com.example.starter.spectrum.domain.LeaseAggregate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 频谱租约协调服务（骨架）。
 */
public interface LeaseService {

    /**
     * 服务调用落定结果：携带首次状态码与响应体，支持幂等重放。
     *
     * @param httpStatus HTTP 状态码
     * @param body       响应体
     * @param leaseId    关联租约标识（可能为空）
     */
    record ServiceResult(int httpStatus, Object body, String leaseId) {
    }

    /** 创建（含请求指纹幂等）。 */
    ServiceResult create(CreateLeaseRequest request);

    /** 修改（追加版本，含 expectedVersion 乐观校验与请求指纹幂等）。 */
    ServiceResult modify(ModifyLeaseRequest request);

    /** 取消（只改变未来有效性；终态同参重复取消无副作用成功）。 */
    ServiceResult cancel(CancelLeaseRequest request);

    /** 指定 UTC 时刻的活动租约快照。 */
    List<LeaseResponse> activeSnapshot(Instant at);

    /** 按区域单元与频段查询指定时刻的占用租约。 */
    List<LeaseResponse> occupancy(Instant at, List<String> regions,
                                  BigDecimal freqLowMHz, BigDecimal freqHighMHz);

    /** 加载租约聚合（控制器转视图用）。 */
    LeaseAggregate requireLease(String leaseId);

    /**
     * 冲突预检（只读，不落库、不消耗幂等键）：返回候选租约与当前活动租约集合的冲突解释。
     *
     * @param regions   候选区域集合
     * @param start     候选开始时刻（含）
     * @param end       候选结束时刻（不含）
     * @param freqLowMHz  候选频率下限（含）
     * @param freqHighMHz 候选频率上限（不含）
     * @param mode      候选模式
     * @param eirpMw    候选 EIRP（mW）
     * @return 冲突明细；空列表表示按当前集合可协调
     */
    java.util.List<ConflictDetail> explain(java.util.Set<String> regions, Instant start, Instant end,
                                           BigDecimal freqLowMHz, BigDecimal freqHighMHz,
                                           com.example.starter.spectrum.LeaseMode mode,
                                           BigDecimal eirpMw);
}
