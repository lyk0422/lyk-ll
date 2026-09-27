package com.example.starter.service;

import com.example.starter.repo.TailDeviceRepository;
import com.example.starter.repo.TailDeviceRow;
import com.example.starter.web.dto.RegisterTailDeviceRequest;
import com.example.starter.web.dto.SelfTestRequest;
import com.example.starter.web.dto.TailDeviceResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 列尾装置档案服务：编号建档、自检合格有效截止更新、装置查询。
 * 装置版本自1起，自检更新等成功变更使版本加1；变更携带期望版本，不符拒绝。
 */
@Service
public class TailDeviceService {

    private final TailDeviceRepository deviceRepository;
    private final IdempotencyService idempotencyService;

    public TailDeviceService(TailDeviceRepository deviceRepository, IdempotencyService idempotencyService) {
        this.deviceRepository = deviceRepository;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 装置建档：编号唯一，初始版本为1。
     */
    public TailDeviceResponse register(RegisterTailDeviceRequest request) {
        return idempotencyService.execute(request.requestId(), "TAIL_DEVICE_REGISTER", request,
                TailDeviceResponse.class, () -> doRegister(request));
    }

    /**
     * 自检更新：携带期望版本，版本不符拒绝；成功后版本加1。
     */
    public TailDeviceResponse updateSelfTest(String deviceNo, SelfTestRequest request) {
        return idempotencyService.execute(request.requestId(), "TAIL_SELF_TEST", request,
                TailDeviceResponse.class, () -> doUpdateSelfTest(deviceNo, request));
    }

    /**
     * 查询装置视图。
     */
    @Transactional(readOnly = true)
    public TailDeviceResponse getDevice(String deviceNo) {
        return toResponse(requireDevice(deviceNo));
    }

    private TailDeviceResponse doRegister(RegisterTailDeviceRequest request) {
        try {
            deviceRepository.insert(request.deviceNo(), request.selfTestValidUntil());
        } catch (DuplicateKeyException e) {
            throw new ConflictException("装置编号已建档：" + request.deviceNo());
        }
        return toResponse(requireDevice(request.deviceNo()));
    }

    private TailDeviceResponse doUpdateSelfTest(String deviceNo, SelfTestRequest request) {
        TailDeviceRow device = requireDevice(deviceNo);
        if (!deviceRepository.updateSelfTest(deviceNo, request.expectedVersion(),
                request.selfTestValidUntil())) {
            throw new ConflictException("装置版本冲突：期望 " + request.expectedVersion()
                    + "，当前 " + device.version());
        }
        return toResponse(requireDevice(deviceNo));
    }

    private TailDeviceRow requireDevice(String deviceNo) {
        return deviceRepository.findByDeviceNo(deviceNo)
                .orElseThrow(() -> new NotFoundException("列尾装置不存在：" + deviceNo));
    }

    private TailDeviceResponse toResponse(TailDeviceRow device) {
        return new TailDeviceResponse(device.deviceNo(), device.selfTestValidUntil(),
                device.currentJobId(), device.version());
    }
}
