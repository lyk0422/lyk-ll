package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.service.FaultReportService;
import com.example.starter.service.GeneratorDispatchService;
import com.example.starter.service.GeneratorTruckService;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.GeneratorDispatchRequest;
import com.example.starter.web.dto.TruckLedgerEntry;
import com.example.starter.web.dto.TruckRegisterRequest;
import com.example.starter.web.dto.TruckUpdateRequest;
import com.example.starter.web.dto.TruckView;

/**
 * 发电车池：登记校验、幂等重放与同键异参、版本变更、容量及占用台账。
 */
class GeneratorTruckServiceTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);

    @Autowired
    private GeneratorTruckService trucks;

    @Autowired
    private GeneratorDispatchService dispatches;

    @Autowired
    private FaultReportService reports;

    @Test
    void 登记发电车并幂等重放同键异参409() {
        TruckView view = trucks.register(new TruckRegisterRequest("t-1", "TK-A1", 500, "F01", null));
        assertTrue(view.available(), "available 为空应默认可用");
        assertEquals(1, view.version());

        TruckView replay = trucks.register(new TruckRegisterRequest("t-1", "TK-A1", 500, "F01", null));
        assertTrue(replay.replayed());
        assertEquals(view.truckId(), replay.truckId());
        assertEquals(1, count("generator_truck", "truck_code = 'TK-A1'"));

        assertThrows(ConflictException.class, () -> trucks.register(
                new TruckRegisterRequest("t-1", "TK-A1", 600, "F01", null)));
        assertEquals(1, count("generator_truck", "truck_code = 'TK-A1'"));
    }

    @Test
    void 登记参数非法与编号重复() {
        assertThrows(IllegalArgumentException.class, () -> trucks.register(
                new TruckRegisterRequest("t-2", "TK-B1", 0, "F01", null)));
        assertThrows(IllegalArgumentException.class, () -> trucks.register(
                new TruckRegisterRequest("t-3", "TK-B2", -100, "F01", null)));
        assertEquals(0, count("generator_idempotency", "request_id IN ('t-2','t-3')"), "失败不占幂等键");

        trucks.register(new TruckRegisterRequest("t-4", "TK-B3", 500, "F01", null));
        assertThrows(UnprocessableException.class, () -> trucks.register(
                new TruckRegisterRequest("t-5", "TK-B3", 500, "F01", null)));
    }

    @Test
    void 变更携带期望版本成功加1冲突409() {
        TruckView view = trucks.register(new TruckRegisterRequest("t-6", "TK-C1", 500, "F01", null));
        TruckView updated = trucks.update(view.truckId(),
                new TruckUpdateRequest("t-7", 1L, 800, null));
        assertEquals(800, updated.capacityKw());
        assertEquals(2, updated.version());

        TruckView replay = trucks.update(view.truckId(), new TruckUpdateRequest("t-7", 1L, 800, null));
        assertTrue(replay.replayed());
        assertEquals(2, replay.version());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM generator_truck WHERE id = ?",
                Integer.class, view.truckId()), "重放不应重复加版本");

        assertThrows(ConflictException.class, () -> trucks.update(view.truckId(),
                new TruckUpdateRequest("t-8", 1L, 900, null)));
        assertThrows(NotFoundException.class, () -> trucks.update(999999L,
                new TruckUpdateRequest("t-9", 1L, 900, null)));

        TruckView unavailable = trucks.update(view.truckId(),
                new TruckUpdateRequest("t-10", 2L, null, false));
        assertFalse(unavailable.available());
        assertEquals(3, unavailable.version());
    }

    @Test
    void 容量及车辆占用台账() {
        freezeAt(T0);
        TruckView truck = trucks.register(new TruckRegisterRequest("t-11", "TK-D1", 1300, "F01", null));
        long order = reports.report(new FaultReportRequest(
                "t-12", "F01", "台账故障点", 30, "市一医院", T0)).workOrderId();
        var dispatch = dispatches.create(new GeneratorDispatchRequest(
                "t-13", order, 1L, List.of(truck.truckId())));

        List<TruckLedgerEntry> ledger = trucks.ledger("F01");
        TruckLedgerEntry entry = ledger.stream()
                .filter(e -> e.truckId() == truck.truckId()).findFirst().orElseThrow();
        assertEquals(dispatch.dispatchId(), entry.occupiedByDispatchId());
        assertEquals(order, entry.occupiedByWorkOrderId());
        assertEquals(T0, entry.occupiedSince());

        assertTrue(trucks.ledger("F02").stream().noneMatch(e -> e.truckId() == truck.truckId()),
                "台账应按馈线过滤");
    }
}
