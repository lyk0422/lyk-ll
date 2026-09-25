package com.example.starter.rolling.service;

import com.example.starter.rolling.api.dto.RollWindowRequest;
import com.example.starter.rolling.api.dto.ScheduleItemRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 排产纯规则校验：宽度跳档边界、烫辊前 5 块、同宽 10 公里恰达、半开区间冲突、违例全量收集。
 */
class ScheduleValidatorTest {

    /** 合成档位：1250 最宽（档1）→ 1000 最窄（档6），每级 50 毫米。 */
    private static final Map<Integer, Integer> GRADES = Map.of(
            1250, 1, 1200, 2, 1150, 3, 1100, 4, 1050, 5, 1000, 6);

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 25, 8, 0);

    private ScheduleItemRequest item(String contract, int width, String km, boolean warmup, int index) {
        return new ScheduleItemRequest(contract, width, new BigDecimal(km), warmup,
                T0.plusHours(index), T0.plusHours(index + 1));
    }

    private RollWindowRequest window(int startHourOffset, int endHourOffset) {
        return new RollWindowRequest(T0.plusHours(startHourOffset), T0.plusHours(endHourOffset));
    }

    @Test
    void 宽度恰跳一级合法且周期编号正确() {
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1250, "3", false, 0),
                        item("C2", 1200, "3", false, 1),
                        item("C3", 1150, "3", false, 2)),
                List.of(), GRADES);

        assertThat(result.valid()).isTrue();
        assertThat(result.violations()).isEmpty();
        assertThat(result.items()).extracting(ScheduleValidator.ValidatedItem::campaignNo)
                .containsExactly(1, 1, 1);
    }

    @Test
    void 宽度跳两级违例() {
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1250, "3", false, 0),
                        item("C2", 1150, "3", false, 1)),
                List.of(), GRADES);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0)).contains("跳档不得超过一级");
    }

    @Test
    void 宽度由窄变宽违例() {
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1200, "3", false, 0),
                        item("C2", 1250, "3", false, 1)),
                List.of(), GRADES);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).singleElement().asString().contains("宽→窄渐变");
    }

    @Test
    void 烫辊材恰在第5块合法() {
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1250, "1", true, 0),
                        item("C2", 1250, "1", true, 1),
                        item("C3", 1200, "1", false, 2),
                        item("C4", 1200, "1", false, 3),
                        item("C5", 1150, "1", true, 4)),
                List.of(), GRADES);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void 烫辊材排在第6块违例() {
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1250, "1", false, 0),
                        item("C2", 1250, "1", false, 1),
                        item("C3", 1200, "1", false, 2),
                        item("C4", 1200, "1", false, 3),
                        item("C5", 1150, "1", false, 4),
                        item("C6", 1150, "1", true, 5)),
                List.of(), GRADES);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).singleElement().asString().contains("前 5 块");
    }

    @Test
    void 同宽度连续轧制恰达10公里合法() {
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1200, "6", false, 0),
                        item("C2", 1200, "4", false, 1)),
                List.of(), GRADES);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void 同宽度连续轧制超过10公里违例() {
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1200, "6", false, 0),
                        item("C2", 1200, "4.001", false, 1)),
                List.of(), GRADES);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).singleElement().asString().contains("10 公里");
    }

    @Test
    void 换辊后同宽度里程与宽度渐变重新起算() {
        // 首周期：1250 两块 4+4=8 公里；窗口 [10:00,11:00) 夹在第2块与第3块之间（端点相接）；
        // 次周期首块 11:00 开轧，宽度重新以 1250 起、里程重新累计，合法。
        var c3 = new ScheduleItemRequest("C3", 1250, new BigDecimal("4"), false,
                T0.plusHours(3), T0.plusHours(4));
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1250, "4", false, 0),
                        item("C2", 1250, "4", false, 1),
                        c3),
                List.of(window(2, 3)), GRADES);

        assertThat(result.valid()).isTrue();
        assertThat(result.items()).extracting(ScheduleValidator.ValidatedItem::campaignNo)
                .containsExactly(1, 1, 2);
        assertThat(result.items().get(2).seqInCampaign()).isEqualTo(1);
    }

    @Test
    void 作业半开区间端点相接合法() {
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1250, "3", false, 0),
                        item("C2", 1200, "3", false, 1)),
                List.of(), GRADES);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void 作业内部重叠违例() {
        var overlapItem = new ScheduleItemRequest("C2", 1200, new BigDecimal("3"), false,
                T0.plusMinutes(59), T0.plusHours(2));
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1250, "3", false, 0), overlapItem),
                List.of(), GRADES);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).singleElement().asString().contains("半开区间端点相接才合法");
    }

    @Test
    void 换辊窗口与作业端点相接合法() {
        // 窗口 [10:00,10:30) 紧贴第2块结束（10:00）与第3块开始（10:30），半开端点相接合法。
        var c3 = new ScheduleItemRequest("C3", 1150, new BigDecimal("3"), false,
                T0.plusHours(2).plusMinutes(30), T0.plusHours(3).plusMinutes(30));
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1250, "3", false, 0),
                        item("C2", 1200, "3", false, 1),
                        c3),
                List.of(new RollWindowRequest(T0.plusHours(2), T0.plusHours(2).plusMinutes(30))),
                GRADES);

        assertThat(result.valid()).isTrue();
        assertThat(result.items()).extracting(ScheduleValidator.ValidatedItem::campaignNo)
                .containsExactly(1, 1, 2);
    }

    @Test
    void 换辊窗口与作业内部重叠违例() {
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1250, "3", false, 0),
                        item("C2", 1200, "3", false, 1)),
                List.of(new RollWindowRequest(T0.plusMinutes(30), T0.plusMinutes(90))), GRADES);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.contains("换辊窗口与第"));
    }

    @Test
    void 换辊窗口落在最后一块之后违例() {
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1250, "3", false, 0)),
                List.of(window(2, 3)), GRADES);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.contains("间隙"));
    }

    @Test
    void 板宽不在档位表违例() {
        var result = ScheduleValidator.validate(
                List.of(item("C1", 999, "3", false, 0)), List.of(), GRADES);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).singleElement().asString().contains("档位表");
    }

    @Test
    void 多个违例全部收集且整单拒绝() {
        // 同宽超里程 + 跳两级 + 合同重复 + 时间重叠，一单子上报全部违例。
        var overlapC4 = new ScheduleItemRequest("C3", 1100, new BigDecimal("8"), true,
                T0.plusMinutes(30), T0.plusMinutes(90));
        var result = ScheduleValidator.validate(
                List.of(item("C1", 1250, "6", false, 0),
                        item("C1", 1250, "5", false, 1),
                        item("C3", 1100, "3", false, 2),
                        overlapC4),
                List.of(), GRADES);

        assertThat(result.valid()).isFalse();
        assertThat(result.violations()).hasSizeGreaterThanOrEqualTo(4);
    }
}
