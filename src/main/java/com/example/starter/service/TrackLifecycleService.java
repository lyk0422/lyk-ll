package com.example.starter.service;

import com.example.starter.domain.AssociationEvidence;
import com.example.starter.domain.Detection;
import com.example.starter.domain.TrackSnapshot;
import com.example.starter.web.dto.AssociateResponse;

import java.util.List;

/**
 * 轨迹关联与状态机应用服务（骨架，实现随后补全）。
 */
public interface TrackLifecycleService {

    /** 提交检测：按门限自动关联，或按 targetTrackUid+expectedVersion 原子显式关联。 */
    AssociateResponse associate(Detection detection, String targetTrackUid, Long expectedVersion, Double gateThreshold);

    /** 显式合法状态迁移（如 OCCLUDED/LOST），按版本原子更新。 */
    TrackSnapshot transition(String trackUid, long expectedVersion, String targetStatus, long eventTime, String reason);

    /** ID 切换：旧轨迹回收，检测由新身份承接，双向保留证据。 */
    AssociateResponse idSwitch(String oldTrackUid, long expectedVersion, Detection detection);

    TrackSnapshot getTrack(String trackUid);

    List<TrackSnapshot> listTracks();

    List<AssociationEvidence> evidence(String trackUid);

    /** 从事件流重放至指定时刻（含），重建全部轨迹快照。 */
    List<TrackSnapshot> replayAsOf(Long asOfInclusive);
}
