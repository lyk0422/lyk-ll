package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.service.GeneratorTruckService;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.dto.RegisterTruckRequest;
import com.example.starter.web.dto.TruckLedgerEntry;
import com.example.starter.web.dto.TruckView;
import com.example.starter.web.dto.UpdateTruckAvailabilityRequest;

/**
 * 发电车池：登记（版本从1开始）、可用性变更（expectedVersion 校验、成功+1）、幂等重放与异参409、占用台账。
 */
class GeneratorTruckServiceTest extends AbstractRepairTest {

    @Autowired
    private GeneratorTruckService trucks;

    @Test
    void 登记发电车版本从1开始且台账可见() {
        TruckView view = trucks.register(new RegisterTruckRequest("t-1", 250, "F03"));
        assertEquals(1, view.version());
        assertTrue(view.available());
        assertEquals(250, view.capacityKw());

        List<TruckLedgerEntry> ledger = trucks.ledger();
        TruckLedgerEntry entry = ledger.stream().filter(e -> e.id() == view.id()).findFirst().orElseThrow();
        assertEquals(250, entry.capacityKw());
        assertEquals("F03", entry.feederId());
        assertEquals(null, entry.dispatchId(), "新车应无占用");
        // 种子车也在台账中
        assertEquals(4, ledger.size());
    }

    @Test
    void 登记幂等重放与异参409() {
        TruckView first = trucks.register(new RegisterTruckRequest("t-2", 100, "F01"));
        TruckView replay = trucks.register(new RegisterTruckRequest("t-2", 100, "F01"));
        assertEquals(first, replay, "同键同参应重放首次冻结结果");
        assertEquals(1, count("generator_truck", "capacity_kw = 100 AND feeder_id = 'F01'"));

        assertThrows(ConflictException.class,
                () -> trucks.register(new RegisterTruckRequest("t-2", 101, "F01")), "同键异参应409");
    }

    @Test
    void 可用性变更校验版本且成功加1() {
        TruckView truck = trucks.register(new RegisterTruckRequest("t-3", 180, "F02"));

        TruckView updated = trucks.updateAvailability(truck.id(),
                new UpdateTruckAvailabilityRequest("t-3-a", 1L, false));
        assertEquals(2, updated.version());
        assertFalse(updated.available());

        assertThrows(ConflictException.class, () -> trucks.updateAvailability(truck.id(),
                new UpdateTruckAvailabilityRequest("t-3-b", 1L, true)), "期望版本过期应409");

        TruckView reenabled = trucks.updateAvailability(truck.id(),
                new UpdateTruckAvailabilityRequest("t-3-b", 2L, true));
        assertEquals(3, reenabled.version());
        assertTrue(reenabled.available());
    }

    @Test
    void 可用性变更幂等重放首次冻结结果() {
        TruckView truck = trucks.register(new RegisterTruckRequest("t-4", 120, "F01"));
        TruckView first = trucks.updateAvailability(truck.id(),
                new UpdateTruckAvailabilityRequest("t-4-a", 1L, false));
        // 再次变更后重放旧键，仍返回首次冻结的版本2结果
        trucks.updateAvailability(truck.id(), new UpdateTruckAvailabilityRequest("t-4-b", 2L, true));
        TruckView replay = trucks.updateAvailability(truck.id(),
                new UpdateTruckAvailabilityRequest("t-4-a", 1L, false));
        assertEquals(first, replay);
        assertEquals(2, replay.version());
        assertEquals(3, trucks.ledger().stream().filter(e -> e.id() == truck.id())
                .findFirst().orElseThrow().version(), "重放不应重复变更");
    }

    @Test
    void 车辆不存在返回404() {
        assertThrows(NotFoundException.class, () -> trucks.updateAvailability(99999L,
                new UpdateTruckAvailabilityRequest("t-5", 1L, false)));
    }

    @Test
    void 结构化指纹对含分隔符文本无歧义() {
        assertNotEquals(Fingerprints.structured("a|", "b"), Fingerprints.structured("a", "|b"));
        assertNotEquals(Fingerprints.structured("a;", "b"), Fingerprints.structured("a", ";b"));
        assertNotEquals(Fingerprints.structured(List.of("x", "y")), Fingerprints.structured(List.of("x;y")));
        assertNotEquals(Fingerprints.structured((Object) null), Fingerprints.structured(""));
        assertEquals(Fingerprints.structured("k", 1, List.of(2L, 3L)),
                Fingerprints.structured("k", 1, List.of(2L, 3L)), "同参应同指纹");
    }
}
