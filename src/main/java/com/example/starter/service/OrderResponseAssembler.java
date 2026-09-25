package com.example.starter.service;

import com.example.starter.domain.BaseStation;
import com.example.starter.domain.DispatchOrder;
import com.example.starter.domain.Generator;
import com.example.starter.repository.MasterDataRepository;
import com.example.starter.web.dto.OrderResponse;
import org.springframework.stereotype.Component;

/**
 * 调度单持久化对象到 API 视图的组装器。
 */
@Component
public class OrderResponseAssembler {

    private final MasterDataRepository masterDataRepository;

    public OrderResponseAssembler(MasterDataRepository masterDataRepository) {
        this.masterDataRepository = masterDataRepository;
    }

    /** 组装调度单视图，油机已释放时 genCode 为 null。 */
    public OrderResponse toResponse(DispatchOrder order, BaseStation station) {
        String genCode = null;
        if (order.genId() != null) {
            genCode = masterDataRepository.findGeneratorById(order.genId())
                    .map(Generator::genCode)
                    .orElse(null);
        }
        return new OrderResponse(
                order.id(),
                order.requestId(),
                station.stationCode(),
                order.eventId(),
                genCode,
                order.status().name(),
                order.arrivalDeadlineUtc(),
                order.dispatchDeadline(),
                order.confirmedAt(),
                order.abnormalAt(),
                order.releasedAt(),
                order.closedAt(),
                order.createdAt()
        );
    }
}
