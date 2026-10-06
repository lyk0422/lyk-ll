package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.service.WorkHourValidator.Interval;

/**
 * 工时规则纯单元测试：连续作业 4 小时强制休息 1 小时（恰满判定）、
 * 单日累计不超过 10 小时（恰 10 小时合法）。
 */
class WorkHourValidatorTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 6, 8, 0);

    private static Interval at(int startHour, int startMinute, long durationMinutes) {
        LocalDateTime start = T0.plusHours(startHour).plusMinutes(startMinute);
        return new Interval(start, start.plusMinutes(durationMinutes));
    }

    @Test
    @DisplayName("连续作业恰满 4 小时后，间隔不足 1 小时开始下一条计划即违规")
    void exactlyFourHoursContinuousRequiresRest() {
        // 已有 08:00-12:00 恰 4 小时连续作业，新计划 12:30 开始（仅休息 30 分钟）
        Optional<String> result = WorkHourValidator.validate(
                List.of(at(0, 0, 240)), at(4, 30, 60));
        assertThat(result).isPresent().get().asString().contains("4 小时");
    }

    @Test
    @DisplayName("连续作业 3 小时 59 分未达 4 小时，间隔不足 1 小时仍可排")
    void underFourHoursContinuousIsAllowed() {
        // 已有 08:00-11:59 连续作业 3 小时 59 分，新计划 12:28 开始（仅休息 29 分钟）
        Optional<String> result = WorkHourValidator.validate(
                List.of(at(0, 0, 239)), at(4, 28, 60));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("连续作业满 4 小时后休息恰满 1 小时可继续排")
    void exactlyOneHourRestSatisfiesRule() {
        Optional<String> result = WorkHourValidator.validate(
                List.of(at(0, 0, 240)), at(5, 0, 60));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("多段计划间隔不足 1 小时串联为连续作业块，累计满 4 小时触发休息")
    void chainedPlansAccumulateContinuousWork() {
        // 08:00-10:00、10:30-12:30（间隔 30 分钟串联，累计 4 小时），新计划 12:50 开始
        Optional<String> result = WorkHourValidator.validate(
                List.of(at(0, 0, 120), at(2, 30, 120)), at(4, 50, 60));
        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("新计划使连续块满 4 小时且后续既有计划间隔不足 1 小时，判定违规")
    void newPlanBridgingBlockViolatesFollowingPlanRest() {
        // 已有 08:00-10:00 与 13:00-14:00（间隔 3 小时，原本各自合法）；
        // 新计划 10:30-12:30 串联成 08:00-14:00 连续 5 小时块，13:00 计划开始前连续作业已达 4 小时
        Optional<String> result = WorkHourValidator.validate(
                List.of(at(0, 0, 120), at(5, 0, 60)), at(2, 30, 120));
        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("单日累计恰 10 小时合法")
    void exactlyTenHoursPerDayIsLegal() {
        // 已有 08:00-12:00、13:00-17:00（各休息满 1 小时），累计 8 小时；新计划 18:00-20:00 恰凑满 10 小时
        Optional<String> result = WorkHourValidator.validate(
                List.of(at(0, 0, 240), at(5, 0, 240)), at(10, 0, 120));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("单日累计超过 10 小时违规")
    void overTenHoursPerDayIsRejected() {
        Optional<String> result = WorkHourValidator.validate(
                List.of(at(0, 0, 240), at(5, 0, 240)), at(10, 0, 121));
        assertThat(result).isPresent().get().asString().contains("10 小时");
    }

    @Test
    @DisplayName("无任何既有计划时新计划合法")
    void emptyExistingIsAllowed() {
        assertThat(WorkHourValidator.validate(List.of(), at(0, 0, 120))).isEmpty();
    }
}
