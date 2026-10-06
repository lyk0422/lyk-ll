package com.example.starter;

import com.example.starter.repo.ExhibitionRepository;
import com.example.starter.service.ExhibitionService;
import com.example.starter.service.MonitoringService;
import com.example.starter.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 展示登记：正常状态可登记，处置期间（紧急/处置中）暂停登记。
 */
class ExhibitionServiceTest extends BaseIntegrationTest {

    @Autowired
    private ExhibitionService exhibitionService;
    @Autowired
    private MonitoringService monitoringService;
    @Autowired
    private ExhibitionRepository exhibitionRepository;

    @Test
    void 正常状态可登记() {
        long id = exhibitionService.register("ZONE-A", "req-exh-1", "晨光研学团", T0);
        assertTrue(id > 0);
        assertEquals(1, exhibitionRepository.countByZone(zoneId("ZONE-A")));
    }

    @Test
    void 处置期间暂停展示登记() {
        for (int i = 0; i < 3; i++) {
            monitoringService.ingest("ZONE-A", "req-exh-vio-" + i, new BigDecimal("4.0"),
                    new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusMinutes(i));
        }
        assertEquals("DISPOSING", zoneStatus("ZONE-A"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> exhibitionService.register("ZONE-A", "req-exh-2", "蓝海旅行团", T0.plusMinutes(3)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status());
        assertEquals(0, exhibitionRepository.countByZone(zoneId("ZONE-A")));
    }
}
