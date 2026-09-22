package com.example.starter.service;

import com.example.starter.domain.AssociationEvidence;
import com.example.starter.domain.Detection;
import com.example.starter.domain.TrackSnapshot;
import com.example.starter.web.dto.AssociateResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 轨迹关联与状态机应用服务外观，委托 {@link TrackLifecycleCore} 的事务方法；
 * 并发互斥（检测键、类别键、轨迹键，均持有到事务提交）在内核内统一完成。
 */
@Service
public class TrackLifecycleServiceImpl implements TrackLifecycleService {

    private final TrackLifecycleCore core;

    public TrackLifecycleServiceImpl(TrackLifecycleCore core) {
        this.core = core;
    }

    @Override
    public AssociateResponse associate(Detection detection, String targetTrackUid,
                                       Long expectedVersion, Double gateThreshold) {
        return core.associateInTx(detection, targetTrackUid, expectedVersion, gateThreshold);
    }

    @Override
    public TrackSnapshot transition(String trackUid, long expectedVersion,
                                    String targetStatus, long eventTime, String reason) {
        return core.transitionInTx(trackUid, expectedVersion, targetStatus, eventTime, reason);
    }

    @Override
    public AssociateResponse idSwitch(String oldTrackUid, long expectedVersion, Detection detection) {
        return core.idSwitchInTx(oldTrackUid, expectedVersion, detection);
    }

    @Override
    public TrackSnapshot getTrack(String trackUid) {
        return core.getTrack(trackUid);
    }

    @Override
    public List<TrackSnapshot> listTracks() {
        return core.listTracks();
    }

    @Override
    public List<AssociationEvidence> evidence(String trackUid) {
        return core.evidence(trackUid);
    }

    @Override
    public List<TrackSnapshot> replayAsOf(Long asOfInclusive) {
        return core.replayAsOf(asOfInclusive);
    }
}
