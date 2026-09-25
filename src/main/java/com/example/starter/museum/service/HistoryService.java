package com.example.starter.museum.service;

import com.example.starter.museum.api.AlertResponse;
import com.example.starter.museum.api.ContractResponse;
import com.example.starter.museum.api.LoanHistoryResponse;
import com.example.starter.museum.api.WorkOrderResponse;
import com.example.starter.museum.domain.po.EnvironmentAlertPo;
import com.example.starter.museum.domain.po.LoanContractPo;
import com.example.starter.museum.domain.po.LoanEventPo;
import com.example.starter.museum.domain.po.ProtectionWorkOrderPo;
import com.example.starter.museum.repo.AlertRepository;
import com.example.starter.museum.repo.CatalogRepository;
import com.example.starter.museum.repo.ContractRepository;
import com.example.starter.museum.repo.EventRepository;
import com.example.starter.museum.repo.WorkOrderRepository;
import com.example.starter.museum.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 借展历史复算：合同状态轨迹（事件流）、越限轨迹（异常列表）与处置时限（工单 deadline）
 * 均从持久化数据重新装配，不依赖内存缓存。
 */
@Service
public class HistoryService {

    private final ContractRepository contracts;
    private final EventRepository events;
    private final AlertRepository alerts;
    private final WorkOrderRepository workOrders;
    private final CatalogRepository catalog;

    public HistoryService(ContractRepository contracts, EventRepository events,
                          AlertRepository alerts, WorkOrderRepository workOrders,
                          CatalogRepository catalog) {
        this.contracts = contracts;
        this.events = events;
        this.alerts = alerts;
        this.workOrders = workOrders;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public LoanHistoryResponse history(String contractNo) {
        LoanContractPo contract = contracts.findByNo(contractNo)
                .orElseThrow(() -> ApiException.notFound("借展合同不存在: " + contractNo));
        String artifactNo = catalog.findArtifactById(contract.artifactId).artifactNo;
        String showcaseNo = contract.showcaseId == null ? null
                : catalog.findShowcaseById(contract.showcaseId).map(s -> s.showcaseNo).orElse(null);
        ContractResponse contractView = new ContractResponse(contract.contractNo, artifactNo,
                contract.borrower, contract.startDate, contract.endDate, contract.status,
                showcaseNo, contract.renewCount, contract.suspendedAt, contract.resumedAt,
                contract.returnedAt, contract.closedAt, contract.version);

        List<LoanHistoryResponse.EventView> eventViews = events.findByContract(contract.id).stream()
                .map(e -> new LoanHistoryResponse.EventView(e.type, e.detail, e.createdAt))
                .toList();

        List<AlertResponse> alertViews = alerts.findByContract(contract.id).stream()
                .map(a -> toAlertResponse(a, contract.contractNo)).toList();

        List<WorkOrderResponse> orderViews = contract.showcaseId == null ? List.of()
                : workOrders.findByShowcase(contract.showcaseId).stream()
                        .map(o -> toOrderResponse(o, contract.showcaseId)).toList();

        return new LoanHistoryResponse(contractView, eventViews, alertViews, orderViews);
    }

    private AlertResponse toAlertResponse(EnvironmentAlertPo po, String contractNo) {
        String showcaseNo = catalog.findShowcaseById(po.showcaseId)
                .map(s -> s.showcaseNo).orElse(null);
        return new AlertResponse(po.alertNo, showcaseNo, contractNo, po.triggerTime, po.status,
                po.escalatedAt, po.recoveredAt, po.resolvedAt);
    }

    private WorkOrderResponse toOrderResponse(ProtectionWorkOrderPo po, Long expectedShowcaseId) {
        String showcaseNo = catalog.findShowcaseById(po.showcaseId)
                .map(s -> s.showcaseNo).orElse(null);
        String alertNo = alerts.findById(po.alertId).map(a -> a.alertNo).orElse(null);
        boolean frozen = catalog.findShowcaseById(po.showcaseId).map(s -> s.frozen).orElse(false);
        return new WorkOrderResponse(po.orderNo, alertNo, showcaseNo, po.status, po.deadline,
                po.confirmedAt, po.handler, po.note, frozen);
    }
}
