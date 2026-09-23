package com.example.starter.coldchain.service;

import com.example.starter.coldchain.dto.CreateShipmentRequest;
import com.example.starter.coldchain.dto.HandoverRequest;
import com.example.starter.coldchain.dto.HandoverResponse;
import com.example.starter.coldchain.dto.ReadingRequest;
import com.example.starter.coldchain.dto.ReadingResponse;
import com.example.starter.coldchain.dto.SegmentEvidenceResponse;
import com.example.starter.coldchain.entity.Shipment;

import java.util.List;

/**
 * 冷链运输交接与温度偏离判定服务。
 */
public interface ColdChainService {

    /** 建货件并打开首段。 */
    Shipment createShipment(CreateShipmentRequest request);

    /** 查货件；不存在抛 404。 */
    Shipment getShipment(String shipmentId);

    /** 写温度读数（支持乱序到达、同序号同参重放、改值 409）。 */
    ReadingResponse writeReading(String shipmentId, ReadingRequest request);

    /** 交接：单事务关闭旧段、判定、打开新段；requestId 幂等。 */
    HandoverResponse handover(String shipmentId, HandoverRequest request);

    /** 全分段证据查询（段元数据 + 段内读数）。 */
    List<SegmentEvidenceResponse> segmentEvidence(String shipmentId);
}
