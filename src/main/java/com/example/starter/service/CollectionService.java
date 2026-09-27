package com.example.starter.service;

import com.example.starter.api.dto.CollectionBatchRequest;
import com.example.starter.api.dto.CollectionBatchResult;
import com.example.starter.api.dto.CollectionCloseRequest;
import com.example.starter.api.dto.CollectionPacketView;
import com.example.starter.api.dto.CollectionSessionRequest;
import com.example.starter.api.dto.CollectionSessionView;
import com.example.starter.api.dto.CollectionWatermarkView;

/**
 * 本地力矩采集会话业务服务。
 *
 * <p>连续水位线语义：序号 2 先到、序号 1 未到时仅缓冲 2，不把它算进见证放行；
 * 水位线只推进到连续完整前缀，缺号包不产生力矩记录、不参与自检/互检/见证放行。
 *
 * <p>新增写操作携带 requestId 与 expectedVersion：同键同参重放首次冻结结果，同键异参 409，
 * 失败不占键（可原键重试）；业务、版本与幂等结果同事务。
 */
public interface CollectionService {

    /** 创建采集会话：绑定工具日校验版本、工序卡与紧固点清单，初始版本 1、水位线 0。 */
    Outcome openSession(CollectionSessionRequest request);

    /** 批量采集上传（至多 20 包）：校验失败整批拒绝；成功则原始包、力矩记录与水位线同事务写入。 */
    Outcome collect(CollectionBatchRequest request);

    /** 关闭会话：必须无缺号且全部缓冲已消费。 */
    Outcome close(CollectionCloseRequest request);

    /** 查询会话（含水印与缓冲统计）。 */
    CollectionSessionView getSession(String sessionNo);

    /** 查询连续水位线。 */
    CollectionWatermarkView getWatermark(String sessionNo);

    /** 查询会话全部原始包证据（按序号升序）。 */
    java.util.List<CollectionPacketView> listPackets(String sessionNo);
}
