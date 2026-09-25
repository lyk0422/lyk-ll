package com.example.starter.hazmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.hazmat.domain.AlertType;
import com.example.starter.hazmat.domain.CheckinResult;
import com.example.starter.hazmat.domain.WaybillStatus;
import com.example.starter.hazmat.error.BusinessException;
import com.example.starter.hazmat.error.ConflictException;
import com.example.starter.hazmat.repo.AlertRepository;
import com.example.starter.hazmat.service.CheckinService;
import com.example.starter.hazmat.service.WaybillService;
import com.example.starter.hazmat.support.HazmatIntegrationTest;
import com.example.starter.hazmat.web.CheckinView;
import com.example.starter.hazmat.web.WaybillView;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 押运打卡测试：去重、幂等、时序归位与逆序拒绝、结案后迟到事件。
 */
class CheckinFlowTest extends HazmatIntegrationTest {

    @Autowired
    private WaybillService waybillService;

    @Autowired
    private CheckinService checkinService;

    @Autowired
    private AlertRepository alerts;

    private long departWaybill(String requestId, String vehicle) {
        WaybillView applied = waybillService.apply(requestId, vehicle, 1,
                routeId("R-LOW"), BASE_TIME.plusHours(1));
        waybillService.approve(applied.id());
        waybillService.depart(applied.id());
        return applied.id();
    }

    @Test
    void 正常打卡_按运单与途经点序号去重() {
        long waybillId = departWaybill("REQ-CK-1", "VEH-101");
        LocalDateTime t1 = BASE_TIME.plusHours(2);
        CheckinView first = checkinService.checkin("CK-1", waybillId, 1, t1);
        assertTrue(first.accepted());
        assertEquals(CheckinResult.ACCEPTED, first.result());

        // 不同 requestId、相同 运单+序号 → 去重冲突
        assertThrows(ConflictException.class,
                () -> checkinService.checkin("CK-2", waybillId, 1, t1.plusMinutes(1)));
    }

    @Test
    void 打卡幂等_同键同参重放_同键异参409() {
        long waybillId = departWaybill("REQ-CK-2", "VEH-102");
        LocalDateTime t1 = BASE_TIME.plusHours(2);
        CheckinView first = checkinService.checkin("CK-3", waybillId, 1, t1);
        CheckinView replay = checkinService.checkin("CK-3", waybillId, 1, t1);
        assertEquals(first.result(), replay.result());
        assertEquals(first.checkinTime(), replay.checkinTime());

        assertThrows(ConflictException.class,
                () -> checkinService.checkin("CK-3", waybillId, 2, t1.plusMinutes(5)));
    }

    @Test
    void 打卡逆序拒绝并告警() {
        long waybillId = departWaybill("REQ-CK-3", "VEH-103");
        LocalDateTime t1 = BASE_TIME.plusHours(2);
        checkinService.checkin("CK-4", waybillId, 1, t1);
        // 第二途经点打卡时刻早于第一途经点 → 逆序拒绝
        assertThrows(BusinessException.class,
                () -> checkinService.checkin("CK-5", waybillId, 2, t1.minusMinutes(1)));
        assertEquals(1, alerts.countByWaybillAndType(waybillId, AlertType.ORDER_VIOLATION));
        List<String> messages = alerts.findMessagesByVehicle("VEH-103");
        assertTrue(messages.stream().anyMatch(m -> m.contains("逆序")));
    }

    @Test
    void 乱序回传按时刻归位() {
        long waybillId = departWaybill("REQ-CK-4", "VEH-104");
        LocalDateTime t1 = BASE_TIME.plusHours(2);
        LocalDateTime t2 = t1.plusMinutes(10);
        // 先回传第二途经点（第一途经点尚未打卡）→ 受理
        CheckinView second = checkinService.checkin("CK-6", waybillId, 2, t2);
        assertTrue(second.accepted());
        // 后回传第一途经点，时刻早于第二途经点 → 按时刻归位，受理
        CheckinView first = checkinService.checkin("CK-7", waybillId, 1, t1);
        assertTrue(first.accepted());
        // 第三途经点时刻早于第二途经点 → 逆序拒绝
        assertThrows(BusinessException.class,
                () -> checkinService.checkin("CK-8", waybillId, 3, t2.minusMinutes(1)));
    }

    @Test
    void 结案后迟到打卡只记迟到事件() {
        long waybillId = departWaybill("REQ-CK-5", "VEH-105");
        checkinService.checkin("CK-9", waybillId, 1, BASE_TIME.plusHours(2));
        waybillService.close(waybillId);

        CheckinView late = checkinService.checkin("CK-10", waybillId, 2,
                BASE_TIME.plusHours(3));
        assertEquals(CheckinResult.LATE_AFTER_CLOSE, late.result());
        assertEquals(1, alerts.countByWaybillAndType(waybillId, AlertType.LATE_AFTER_CLOSE));
        // 终态保持结案
        assertEquals(WaybillStatus.CLOSED, waybillService.getView(waybillId).status());
        // 迟到事件同样幂等
        CheckinView replay = checkinService.checkin("CK-10", waybillId, 2,
                BASE_TIME.plusHours(3));
        assertEquals(CheckinResult.LATE_AFTER_CLOSE, replay.result());
    }
}
