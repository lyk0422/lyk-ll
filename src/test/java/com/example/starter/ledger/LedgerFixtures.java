package com.example.starter.ledger;

import com.example.starter.ledger.service.LedgerService;
import com.example.starter.ledger.web.InboundRequest;
import com.example.starter.ledger.web.InspectionRequest;
import com.example.starter.ledger.web.RegisterBatteryRequest;

/**
 * 测试用合成数据流程辅助：建档 → 入站 → 检测合格上架。
 */
public final class LedgerFixtures {

    public static final String STATION_A = "ST-A";
    public static final String STATION_B = "ST-B";

    private LedgerFixtures() {
    }

    /** 建档。 */
    public static void register(LedgerService service, String code) {
        RegisterBatteryRequest request = new RegisterBatteryRequest();
        request.setCode(code);
        service.registerBattery(request);
    }

    /** 回站入站。 */
    public static void inbound(LedgerService service, String requestId, String code, String station) {
        InboundRequest request = new InboundRequest();
        request.setRequestId(requestId);
        request.setBatteryCode(code);
        request.setStationCode(station);
        service.inbound(request);
    }

    /** 检测合格并上架（SOC=95、健康度=90）。 */
    public static void inspectPass(LedgerService service, String requestId, String code) {
        inspect(service, requestId, code, 95, 90);
    }

    /** 指定检测值。 */
    public static void inspect(LedgerService service, String requestId, String code, int soc, int health) {
        InspectionRequest request = new InspectionRequest();
        request.setRequestId(requestId);
        request.setBatteryCode(code);
        request.setSoc(soc);
        request.setHealth(health);
        service.inspect(request);
    }

    /** 一步到位：建档 → 入站 ST-A → 合格上架。 */
    public static void readyAvailableAtA(LedgerService service, String code) {
        register(service, code);
        inbound(service, "in-" + code, code, STATION_A);
        inspectPass(service, "insp-" + code, code);
    }
}
