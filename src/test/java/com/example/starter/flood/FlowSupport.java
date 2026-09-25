package com.example.starter.flood;

import com.example.starter.flood.bootstrap.SyntheticDataInitializer;
import com.example.starter.flood.service.OrderService;
import com.example.starter.flood.service.PlanService;
import com.example.starter.flood.service.RoutingService;
import com.example.starter.flood.service.ReviewService;
import com.example.starter.flood.service.WarningService;
import com.example.starter.flood.web.dto.OpeningItem;
import com.example.starter.flood.web.dto.OrderActionRequest;
import com.example.starter.flood.web.dto.PlanRequest;
import com.example.starter.flood.web.dto.PlanView;
import com.example.starter.flood.web.dto.ReceiptRequest;
import com.example.starter.flood.web.dto.ReviewRequest;
import com.example.starter.flood.web.dto.RoutingRequest;
import com.example.starter.flood.web.dto.RoutingView;
import com.example.starter.flood.web.dto.WarningRequest;
import com.example.starter.flood.web.dto.WarningView;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 测试流程构造辅助：串起“预警→演算→方案→签收→执行→回执→归档”，
 * 各步可单独替换参数以构造边界场景。
 */
@Component
public class FlowSupport {

    @Autowired
    private WarningService warningService;
    @Autowired
    private RoutingService routingService;
    @Autowired
    private PlanService planService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private ReviewService reviewService;

    public String pondCode() {
        return SyntheticDataInitializer.DEMO_POND_CODE;
    }

    public WarningView openWarning(String requestId) {
        return warningService.open(new WarningRequest(requestId, pondCode(),
                "合成暴雨橙色预警", "未来 6 小时预报降雨 120 毫米"));
    }

    public WarningView warningWithDifferentTitle(String requestId) {
        return warningService.open(new WarningRequest(requestId, pondCode(),
                "同键不同标题的红色预警", "参数已变化，应触发 409"));
    }

    public RoutingView route(String requestId, String warningNo, BigDecimal waterLevel,
                             BigDecimal designFloodVolume, BigDecimal dryBeach,
                             BigDecimal phreatic) {
        return routingService.route(new RoutingRequest(requestId, pondCode(), warningNo,
                waterLevel, designFloodVolume, dryBeach, phreatic));
    }

    public PlanView createPlan(String requestId, String warningNo, String routingNo,
                               List<OpeningItem> openings) {
        return planService.create(new PlanRequest(requestId, warningNo, routingNo, openings));
    }

    public void sign(String orderNo, String operator) {
        orderService.sign(orderNo, new OrderActionRequest(operator));
    }

    public void execute(String orderNo, String operator) {
        orderService.execute(orderNo, new OrderActionRequest(operator));
    }

    public void receipt(String orderNo, String requestId, String operator,
                        List<OpeningItem> actual, String reason) {
        orderService.receipt(orderNo, new ReceiptRequest(requestId, operator, actual, reason));
    }

    public void archive(String requestId, String warningNo, String note) {
        reviewService.archive(new ReviewRequest(requestId, warningNo, note));
    }

    /** 单孔口泄量恰为下游安全泄量 20 m3/s（合法边界）。 */
    public static List<OpeningItem> safeBoundaryOpenings() {
        return List.of(new OpeningItem("1#排洪隧洞工作闸门", new BigDecimal("1.200"),
                new BigDecimal("20.000")));
    }
}
