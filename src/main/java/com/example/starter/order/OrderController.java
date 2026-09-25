package com.example.starter.order;

import com.example.starter.order.OrderDtos.ConfirmRequest;
import com.example.starter.order.OrderDtos.DraftRequest;
import com.example.starter.order.OrderDtos.IssueRequest;
import com.example.starter.order.OrderDtos.OrderDetail;
import com.example.starter.order.OrderDtos.OrderView;
import com.example.starter.order.OrderDtos.ReviewRequest;
import com.example.starter.order.OrderDtos.TraceView;
import com.example.starter.order.OrderDtos.UnlockRequest;
import com.example.starter.order.OrderDtos.UnlockView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 倒闸操作票 REST 入口。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /** 拟票（requestId 幂等）。 */
    @PostMapping
    public ResponseEntity<String> draft(@RequestBody DraftRequest req) {
        return orderService.draft(req);
    }

    /** 审核。 */
    @PostMapping("/{orderId}/review")
    public OrderView review(@PathVariable long orderId, @RequestBody ReviewRequest req) {
        return orderService.review(orderId, req);
    }

    /** 预发（requestId 幂等，同间隔并发唯一成功）。 */
    @PostMapping("/{orderId}/issue")
    public ResponseEntity<String> issue(@PathVariable long orderId, @RequestBody IssueRequest req) {
        return orderService.issue(orderId, req);
    }

    /** 步确认（requestId 幂等，双人确认）。 */
    @PostMapping("/{orderId}/steps/{seq}/confirm")
    public ResponseEntity<String> confirm(@PathVariable long orderId, @PathVariable int seq,
                                          @RequestBody ConfirmRequest req) {
        return orderService.confirm(orderId, seq, req);
    }

    /** 归档（终态，重复归档幂等成功）。 */
    @PostMapping("/{orderId}/archive")
    public OrderView archive(@PathVariable long orderId) {
        return orderService.archive(orderId);
    }

    /** 紧急解锁（双人授权，解锁与闭锁恢复同事务）。 */
    @PostMapping("/{orderId}/unlock")
    public UnlockView unlock(@PathVariable long orderId, @RequestBody UnlockRequest req) {
        return orderService.unlock(orderId, req);
    }

    /** 操作票详情。 */
    @GetMapping("/{orderId}")
    public OrderDetail get(@PathVariable long orderId) {
        return orderService.get(orderId);
    }

    /** 轨迹：事件、闭锁命中与解锁记录。 */
    @GetMapping("/{orderId}/trace")
    public TraceView trace(@PathVariable long orderId) {
        return orderService.trace(orderId);
    }
}
