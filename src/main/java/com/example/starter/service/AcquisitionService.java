package com.example.starter.service;

import java.util.List;

import com.example.starter.api.dto.AcquisitionBatchRequest;
import com.example.starter.api.dto.AcquisitionBatchResult;
import com.example.starter.api.dto.AcquisitionBatchView;
import com.example.starter.api.dto.AcquisitionPacketView;
import com.example.starter.api.dto.AcquisitionSessionCloseRequest;
import com.example.starter.api.dto.AcquisitionSessionCreateRequest;
import com.example.starter.api.dto.AcquisitionSessionView;
import com.example.starter.api.dto.AcquisitionWatermarkView;

/**
 * 本地力矩采集服务：设备不联网，数据仅经本地 HTTP 提交。
 *
 * <p>连续水位线语义：同会话包按从 1 开始的连续序号入账；序号 2 先到、序号 1 未到时
 * 仅缓冲 2（水位线之后第 1~20 号的乱序窗口内），水位线只推进到连续完整前缀，
 * 缺号后的缓冲包不参与自检、互检或见证放行。
 *
 * <p>所有写操作经幂等执行器（失败不占键），并携带 expectedVersion 乐观版本。
 */
public interface AcquisitionService {

    /** 创建采集会话：绑定工序卡、工具日校验版本快照与紧固点清单。 */
    Outcome createSession(AcquisitionSessionCreateRequest request);

    /** 批量上传（至多 20 包）：序号重复、未知点或超窗任一错误整批拒绝。 */
    Outcome uploadBatch(String sessionNo, AcquisitionBatchRequest request);

    /** 关闭会话：必须无缺号且全部缓冲已消费，否则拒绝。 */
    Outcome closeSession(String sessionNo, AcquisitionSessionCloseRequest request);

    /** 会话查询。 */
    AcquisitionSessionView getSession(String sessionNo);

    /** 水位线查询。 */
    AcquisitionWatermarkView getWatermark(String sessionNo);

    /** 包证据查询（含缓冲/入账/迟到状态与内容指纹）。 */
    List<AcquisitionPacketView> listPackets(String sessionNo);

    /** 批次证据查询。 */
    List<AcquisitionBatchView> listBatches(String sessionNo);
}
