package com.example.starter.sync.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.sync.SyncErrorCode;
import com.example.starter.sync.SyncException;
import com.example.starter.sync.domain.FrameInput;
import com.example.starter.sync.domain.FusionFrame;
import com.example.starter.sync.engine.SyncEngine;
import com.example.starter.sync.repo.EventRepository;
import com.example.starter.sync.repo.FrameRepository;
import com.example.starter.sync.repo.SyncEventRow;

/**
 * 融合帧服务：按目标融合时刻提交不可变融合帧，并持久化帧与输入证据。
 */
@Service
public class FusionService {

    private final SyncEngine engine;
    private final FrameRepository frameRepository;
    private final EventRepository eventRepository;

    public FusionService(SyncEngine engine, FrameRepository frameRepository,
                         EventRepository eventRepository) {
        this.engine = engine;
        this.frameRepository = frameRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * 提交目标时刻的融合帧。仅当全部必需传感器满足各自窗口（或可插值）才可提交；
     * 提交后帧不可变，重复提交抛出 FRAME_ALREADY_COMMITTED。
     */
    @Transactional
    public FusionFrame commit(long targetTs) {
        FusionFrame computed = engine.commit(targetTs);
        Optional<Long> frameId = frameRepository.tryInsertFrame(targetTs);
        if (frameId.isEmpty()) {
            throw new SyncException(SyncErrorCode.FRAME_ALREADY_COMMITTED,
                    "fusion frame already committed at target " + targetTs);
        }
        for (FrameInput input : computed.inputs()) {
            frameRepository.insertInput(frameId.get(), input);
        }
        eventRepository.append(SyncEventRow.FRAME_COMMITTED, null, null, targetTs,
                null, null, null, null, null);
        return new FusionFrame(frameId.get(), targetTs, computed.inputs());
    }

    /**
     * 查询已提交的融合帧及其输入证据。
     */
    @Transactional(readOnly = true)
    public FusionFrame getFrame(long targetTs) {
        return frameRepository.findFrame(targetTs)
                .orElseThrow(() -> new SyncException(SyncErrorCode.FRAME_NOT_FOUND,
                        "no committed fusion frame at target " + targetTs));
    }
}
