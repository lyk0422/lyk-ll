package com.example.starter.pest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.common.ApiException;
import com.example.starter.pest.api.Requests.OutboundRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 分区拦截：处置期间只禁该库区出库登记，其它库区借阅不受影响。
 */
class OutboundBlockingTest extends BaseIntegrationTest {

    @Test
    void 处置期间仅禁止该库区出库_其它库区不受影响() {
        reportAndConfirm("A", "o1");

        assertThatThrownBy(() -> outboundService.register(new OutboundRequest("o1-out-a", "A", "档案盒-1")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        var otherZone = outboundService.register(new OutboundRequest("o1-out-b", "B", "档案盒-2"));
        assertThat(otherZone.id()).isPositive();
        assertThat(otherZone.zoneCode()).isEqualTo("B");
        assertThat(count("outbound_registration")).isEqualTo(1);
    }

    @Test
    void 无处置链的库区可正常出库() {
        var result = outboundService.register(new OutboundRequest("o2-out", "D", "档案盒-9"));
        assertThat(result.id()).isPositive();
        assertThat(count("outbound_registration")).isEqualTo(1);
    }
}
