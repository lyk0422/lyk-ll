package com.example.starter.service;

import com.example.starter.domain.ZonePool;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.ExhibitionRepository;
import com.example.starter.repo.ZoneRepository;
import com.example.starter.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 展示登记服务：处置期间（紧急/处置中）暂停登记。
 */
@Service
public class ExhibitionService {

    private final ZoneRepository zoneRepository;
    private final ExhibitionRepository exhibitionRepository;

    public ExhibitionService(ZoneRepository zoneRepository, ExhibitionRepository exhibitionRepository) {
        this.zoneRepository = zoneRepository;
        this.exhibitionRepository = exhibitionRepository;
    }

    /**
     * 登记展示；紧急或处置中的展示池暂停登记，返回 422。
     */
    @Transactional
    public long register(String zoneCode, String requestId, String groupName, LocalDateTime registeredAt) {
        ZonePool zone = zoneRepository.findByCode(zoneCode)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "展示池不存在: " + zoneCode));
        if (zone.status() == ZoneStatus.EMERGENCY || zone.status() == ZoneStatus.DISPOSING) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "展示池处于 " + zone.status() + " 处置期间，暂停展示登记");
        }
        return exhibitionRepository.insert(zone.id(), groupName, registeredAt, requestId);
    }
}
