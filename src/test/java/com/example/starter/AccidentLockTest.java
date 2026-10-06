package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.domain.Circuit;
import com.example.starter.domain.CircuitStatus;
import com.example.starter.dto.AccidentLockRequest;
import com.example.starter.dto.AccidentUnlockRequest;
import com.example.starter.dto.BrightnessRequest;
import com.example.starter.dto.DimRequest;
import com.example.starter.dto.DimResult;
import com.example.starter.exception.BizException;
import com.example.starter.repo.AccidentRepository;
import com.example.starter.repo.RecordRepository;
import com.example.starter.service.DimmingService;

/**
 * 事故锁定测试：锁定后普通调光不得覆盖锁定值，解除须现场确认，锁定与解除同事务留痕。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tl_test_accident;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class AccidentLockTest extends BaseIntegrationTest {

    @Autowired
    private DimmingService service;

    @Autowired
    private AccidentRepository accidentRepo;

    @Autowired
    private RecordRepository recordRepo;

    @Test
    void 事故锁定_普通调光不得覆盖_解除须现场确认_同事务留痕() {
        // 事故告警：锁定在事故模式（最高档），不受稳定期限制
        DimResult lock = service.accidentLock(1L, new AccidentLockRequest("r-ac-1", "op-zhang"));
        assertEquals(DimResult.STATUS_EXECUTED, lock.status());
        assertEquals(3, lock.fromLevel());
        assertEquals(5, lock.toLevel());
        Circuit locked = service.getCircuit(1L);
        assertEquals(CircuitStatus.ACCIDENT, locked.status());
        assertEquals(5, locked.lockedLevel());
        assertEquals(5, locked.currentLevel());

        // 普通人工调光不得覆盖锁定值
        BizException ex = assertThrows(BizException.class,
                () -> service.dimManually(1L, new DimRequest("r-ac-2", 1)));
        assertEquals("ACCIDENT_LOCKED", ex.code());
        assertEquals(409, ex.status().value());
        assertEquals(5, service.getCircuit(1L).currentLevel());

        // 亮度触发同样不得覆盖锁定值
        ex = assertThrows(BizException.class,
                () -> service.checkBrightness(1L, new BrightnessRequest("r-ac-3", 100)));
        assertEquals("ACCIDENT_LOCKED", ex.code());
        assertEquals(5, service.getCircuit(1L).currentLevel());

        // 解除须现场确认
        ex = assertThrows(BizException.class,
                () -> service.accidentUnlock(1L, new AccidentUnlockRequest("r-ac-4", " ")));
        assertEquals("CONFIRM_REQUIRED", ex.code());
        assertEquals(400, ex.status().value());
        assertEquals(CircuitStatus.ACCIDENT, service.getCircuit(1L).status());

        // 现场确认后解除
        DimResult unlock = service.accidentUnlock(1L, new AccidentUnlockRequest("r-ac-5", "confirm-li"));
        assertEquals(DimResult.STATUS_EXECUTED, unlock.status());
        Circuit normal = service.getCircuit(1L);
        assertEquals(CircuitStatus.NORMAL, normal.status());
        assertNull(normal.lockedLevel());

        // 锁定与解除在同一事务内留痕
        List<AccidentRepository.AccidentEvent> events = accidentRepo.findByCircuit(1L);
        assertEquals(2, events.size());
        assertEquals("LOCK", events.get(0).action());
        assertEquals(5, events.get(0).level());
        assertEquals("op-zhang", events.get(0).operator());
        assertEquals("r-ac-1", events.get(0).requestId());
        assertEquals("UNLOCK", events.get(1).action());
        assertEquals("confirm-li", events.get(1).operator());

        // 事故调光留痕含前后档位与触发来源
        assertEquals(1, recordRepo.countByCircuit(1L));
        RecordRepository.DimRecord record = recordRepo
                .findLatestBefore(1L, clock.instant().plusSeconds(1)).orElseThrow();
        assertEquals(3, record.fromLevel());
        assertEquals(5, record.toLevel());
        assertEquals("ACCIDENT", record.source());

        // 解除且稳定期满后恢复普通调光
        clock.advance(Duration.ofMinutes(10));
        DimResult dim = service.dimManually(1L, new DimRequest("r-ac-6", 2));
        assertEquals(DimResult.STATUS_EXECUTED, dim.status());
        assertEquals(5, dim.fromLevel());
        assertEquals(2, dim.toLevel());
    }

    @Test
    void 重复锁定与未锁定解除均拒绝() {
        service.accidentLock(1L, new AccidentLockRequest("r-al-1", "op"));
        BizException ex = assertThrows(BizException.class,
                () -> service.accidentLock(1L, new AccidentLockRequest("r-al-2", "op")));
        assertEquals("ALREADY_LOCKED", ex.code());

        service.accidentUnlock(1L, new AccidentUnlockRequest("r-al-3", "cf"));
        ex = assertThrows(BizException.class,
                () -> service.accidentUnlock(1L, new AccidentUnlockRequest("r-al-4", "cf")));
        assertEquals("NOT_LOCKED", ex.code());
    }
}
