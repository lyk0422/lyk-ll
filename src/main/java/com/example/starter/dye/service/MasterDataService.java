package com.example.starter.dye.service;

import com.example.starter.dye.domain.DyeVatPO;
import com.example.starter.dye.domain.DyestuffPO;
import com.example.starter.dye.repo.DyeVatRepository;
import com.example.starter.dye.repo.DyestuffRepository;
import com.example.starter.dye.web.NotFoundException;
import com.example.starter.dye.web.dto.DyestuffResponse;
import com.example.starter.dye.web.dto.DyestuffUpsertRequest;
import com.example.starter.dye.web.dto.VatResponse;
import com.example.starter.dye.web.dto.VatUpsertRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 合成主数据维护：染化料库存与染缸设备。
 */
@Service
public class MasterDataService {

    private final DyestuffRepository dyestuffRepository;
    private final DyeVatRepository dyeVatRepository;
    private final Clock clock;

    public MasterDataService(DyestuffRepository dyestuffRepository, DyeVatRepository dyeVatRepository,
                             Clock clock) {
        this.dyestuffRepository = dyestuffRepository;
        this.dyeVatRepository = dyeVatRepository;
        this.clock = clock;
    }

    @Transactional
    public DyestuffResponse upsertDyestuff(DyestuffUpsertRequest request) {
        dyestuffRepository.upsert(request.code(), request.name(), request.stockGrams(),
                LocalDateTime.now(clock));
        return toDyestuff(dyestuffRepository.findByCode(request.code()).orElseThrow());
    }

    public DyestuffResponse getDyestuff(String code) {
        return dyestuffRepository.findByCode(code)
                .map(this::toDyestuff)
                .orElseThrow(() -> new NotFoundException("染化料不存在: " + code));
    }

    @Transactional
    public VatResponse upsertVat(VatUpsertRequest request) {
        dyeVatRepository.upsert(request.vatCode(), request.capacityKg());
        return toVat(dyeVatRepository.findByCode(request.vatCode()).orElseThrow());
    }

    public VatResponse getVat(String vatCode) {
        return dyeVatRepository.findByCode(vatCode)
                .map(this::toVat)
                .orElseThrow(() -> new NotFoundException("染缸不存在: " + vatCode));
    }

    public List<DyestuffResponse> listDyestuffs() {
        return dyestuffRepository.all().stream().map(this::toDyestuff).toList();
    }

    private DyestuffResponse toDyestuff(DyestuffPO po) {
        return new DyestuffResponse(po.getCode(), po.getName(), po.getStockGrams());
    }

    private VatResponse toVat(DyeVatPO po) {
        String activeRunNo = null;
        if (po.getActiveRunId() != null) {
            activeRunNo = dyeVatRepository.findActiveRunNo(po.getActiveRunId());
        }
        return new VatResponse(po.getId(), po.getVatCode(), po.getCapacityKg(), activeRunNo);
    }
}
