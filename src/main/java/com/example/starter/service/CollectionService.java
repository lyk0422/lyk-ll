package com.example.starter.service;

import java.util.List;

import com.example.starter.api.dto.CollectionBatchView;
import com.example.starter.api.dto.CollectionCloseRequest;
import com.example.starter.api.dto.CollectionPacketView;
import com.example.starter.api.dto.CollectionSessionCreateRequest;
import com.example.starter.api.dto.CollectionSessionView;
import com.example.starter.api.dto.CollectionUploadRequest;

/**
 * 本地工具采集协议服务：有序批次入账与乱序水位线。
 *
 * <p>所有写操作携带 requestId 与 expectedVersion：同键同参重放首次冻结结果，异参 409，
 * 失败不占键；业务、版本与幂等结果同事务。设备不联网，数据仅经本地 HTTP 提交，
 * 全部为合成数据，仅存于 JVM 内存。
 */
public interface CollectionService {

    /** 创建采集会话：绑定工序卡、工具及其日校验版本，快照紧固点清单；版本从 1 开始。 */
    Outcome createSession(CollectionSessionCreateRequest request);

    /** 批量上传采集包：整批校验，任一错误整批拒绝；按连续完整前缀入账并推进水位线。 */
    Outcome upload(CollectionUploadRequest request);

    /** 关闭会话：必须无缺号且全部缓冲已消费。 */
    Outcome closeSession(CollectionCloseRequest request);

    /** 查询会话（含水位线、版本与包状态统计）。 */
    CollectionSessionView getSession(String sessionNo);

    /** 查询会话的原始包证据（按序号升序）。 */
    List<CollectionPacketView> listPackets(String sessionNo);

    /** 查询会话的上传批次台账。 */
    List<CollectionBatchView> listBatches(String sessionNo);
}
