package com.example.starter.service;

import java.io.IOException;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.starter.repo.AliquotRow;
import com.example.starter.repo.CustodyEventRow;
import com.example.starter.repo.ReservationRow;
import com.example.starter.repo.SpecimenRow;
import com.example.starter.support.Volume;
import com.example.starter.web.dto.AliquotView;
import com.example.starter.web.dto.CustodyEventView;
import com.example.starter.web.dto.ReservationView;
import com.example.starter.web.dto.SpecimenView;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 行模型到对外视图的组装：纳升整数格式化为微升字符串，结果载荷由 JSON 还原。
 */
@Component
public class ViewAssembler {

    private final ObjectMapper objectMapper;

    public ViewAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SpecimenView toSpecimenView(SpecimenRow row) {
        return new SpecimenView(
                row.specimenId(),
                Volume.formatUl(row.volumeNl()),
                Volume.formatUl(row.remainingNl()),
                row.status().name(),
                row.custodian(),
                row.version(),
                row.createdAt(),
                row.updatedAt());
    }

    /**
     * @param reservedNl 该 aliquot 当前 RESERVED 占用合计（纳升），由调用方在锁内或快照后统计
     */
    public AliquotView toAliquotView(AliquotRow row, long reservedNl) {
        long availableNl = row.volumeNl() - row.consumedNl() - reservedNl;
        return new AliquotView(
                row.aliquotId(),
                row.specimenId(),
                Volume.formatUl(row.volumeNl()),
                Volume.formatUl(row.consumedNl()),
                Volume.formatUl(reservedNl),
                Volume.formatUl(availableNl),
                row.status().name(),
                row.custodian(),
                row.version(),
                row.createdAt(),
                row.updatedAt());
    }

    public ReservationView toReservationView(ReservationRow row) {
        return new ReservationView(
                row.requestId(),
                row.aliquotId(),
                row.projectCode(),
                Volume.formatUl(row.volumeNl()),
                row.status().name(),
                row.custodian(),
                row.version(),
                parseResult(row.resultPayload()),
                row.resultAt(),
                row.createdAt(),
                row.updatedAt());
    }

    public CustodyEventView toEventView(CustodyEventRow row) {
        return new CustodyEventView(
                row.entityType().name(),
                row.entityId(),
                row.eventKind().name(),
                row.fromCustodian(),
                row.toCustodian(),
                row.versionBefore(),
                row.versionAfter(),
                row.occurredAt(),
                row.note());
    }

    private Object parseResult(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (IOException ex) {
            return Map.of("raw", json);
        }
    }
}
