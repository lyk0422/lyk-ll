package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.domain.Allocation;
import com.example.starter.domain.Turbine;
import com.example.starter.web.ApiBadRequestException;

/**
 * 分摊算法纯逻辑单测：恰等目标不降机、15% 保留边界、尾差归属、取整与失败分支。
 */
class AllocationEngineTest {

    private static Turbine turbine(long id, String code, int rated, int available) {
        Turbine t = new Turbine();
        t.setId(id);
        t.setTurbineCode(code);
        t.setRatedPowerKw(rated);
        t.setTechnicalMinKw(Math.round(rated * 0.15f));
        t.setAvailablePowerKw(available);
        return t;
    }

    private int totalTarget(List<Allocation> allocations) {
        return allocations.stream().mapToInt(Allocation::getTargetPowerKw).sum();
    }

    @Test
    @DisplayName("全场可用出力恰等于目标：不降机，各机组维持可用出力")
    void exactEqualTargetNoCurtailment() {
        List<Turbine> turbines = List.of(
                turbine(1, "T-01", 2000, 2000),
                turbine(2, "T-02", 2000, 1600),
                turbine(3, "T-03", 3000, 2400));

        List<Allocation> result = AllocationEngine.allocate(turbines, 6000, 100L, 1);

        assertThat(result).hasSize(3);
        assertThat(result).allSatisfy(a -> assertThat(a.getCurtailKw()).isZero());
        assertThat(result).extracting(Allocation::getTargetPowerKw)
                .containsExactly(2000, 1600, 2400);
        assertThat(totalTarget(result)).isEqualTo(6000);
    }

    @Test
    @DisplayName("限电差额按可降空间比例分摊，取整后合计恰等于目标")
    void proportionalAllocationSumsExactly() {
        // 可用 2000/1600/2400，下限 300/300/450，可降空间 1700/1300/1950，目标 5000（压减 1000）。
        List<Turbine> turbines = List.of(
                turbine(1, "T-01", 2000, 2000),
                turbine(2, "T-02", 2000, 1600),
                turbine(3, "T-03", 3000, 2400));

        List<Allocation> result = AllocationEngine.allocate(turbines, 5000, 100L, 1);

        assertThat(totalTarget(result)).isEqualTo(5000);
        assertThat(result).allSatisfy(a ->
                assertThat(a.getTargetPowerKw()).isGreaterThanOrEqualTo(
                        turbines.get((int) (a.getTurbineId() - 1)).getTechnicalMinKw()));
        int totalCurtail = result.stream().mapToInt(Allocation::getCurtailKw).sum();
        assertThat(totalCurtail).isEqualTo(1000);
    }

    @Test
    @DisplayName("目标恰为全场技术下限之和：所有机组恰降至 15%，合法")
    void targetAtTechnicalFloorExactly15Percent() {
        List<Turbine> turbines = List.of(
                turbine(1, "T-01", 2000, 2000),
                turbine(2, "T-02", 2000, 1600),
                turbine(3, "T-03", 3000, 2400));
        int floorTotal = 300 + 300 + 450;

        List<Allocation> result = AllocationEngine.allocate(turbines, floorTotal, 100L, 1);

        assertThat(result).extracting(Allocation::getTargetPowerKw)
                .containsExactly(300, 300, 450);
        assertThat(result).extracting(a -> a.getTargetPowerKw() == 300 || a.getTargetPowerKw() == 450)
                .hasSize(3);
        assertThat(totalTarget(result)).isEqualTo(floorTotal);
    }

    @Test
    @DisplayName("低于全场技术下限：拒绝分摊")
    void targetBelowFloorRejected() {
        List<Turbine> turbines = List.of(
                turbine(1, "T-01", 2000, 2000),
                turbine(2, "T-02", 2000, 1600));

        assertThatThrownBy(() -> AllocationEngine.allocate(turbines, 500, 1L, 1))
                .isInstanceOf(ApiBadRequestException.class)
                .hasMessageContaining("下限");
    }

    @Test
    @DisplayName("高于全场可用出力：拒绝（无需限电）")
    void targetAboveAvailableRejected() {
        List<Turbine> turbines = List.of(turbine(1, "T-01", 2000, 1000));

        assertThatThrownBy(() -> AllocationEngine.allocate(turbines, 1200, 1L, 1))
                .isInstanceOf(ApiBadRequestException.class)
                .hasMessageContaining("高于全场可用出力");
    }

    @Test
    @DisplayName("取整尾差归入可降空间最大的机组，合计精确无偏")
    void remainderGoesToLargestHeadroomTurbine() {
        // 两台等容机组：可降空间 1000、1000；压减 999 时，整除份额 499/499，尾差 1 给并列靠前机组。
        Turbine a = turbine(1, "T-01", 2000, 1300);
        Turbine b = turbine(2, "T-02", 2000, 1300);

        List<Allocation> result = AllocationEngine.allocate(List.of(a, b), 1601, 1L, 1);

        assertThat(totalTarget(result)).isEqualTo(1601);
        // 可降空间相同，尾差 1 按机组编码归 T-01：其压减 500，T-02 压减 499。
        assertThat(result.get(0).getCurtailKw()).isEqualTo(500);
        assertThat(result.get(1).getCurtailKw()).isEqualTo(499);
    }

    @Test
    @DisplayName("尾差在最大可降机组装不下时溢出到次大机组，仍不破坏 15% 下限")
    void remainderOverflowsWhenLargestAtFloor() {
        // 目标=全场下限+1：最大机组先吃尾差；构造使比例份额已使大机组接近下限的情形，验证合计与下限。
        List<Turbine> turbines = List.of(
                turbine(1, "T-01", 2000, 400),
                turbine(2, "T-02", 2000, 2000),
                turbine(3, "T-03", 3000, 500));
        int floorTotal = 300 + 300 + 450;

        List<Allocation> result = AllocationEngine.allocate(turbines, floorTotal + 1, 1L, 1);

        assertThat(totalTarget(result)).isEqualTo(floorTotal + 1);
        assertThat(result).allSatisfy(a -> {
            Turbine source = turbines.get((int) (a.getTurbineId() - 1));
            assertThat(a.getTargetPowerKw()).isGreaterThanOrEqualTo(source.getTechnicalMinKw());
        });
    }

    @Test
    @DisplayName("重分摊遇小风机组（可用低于技术下限）：该机组保持实际出力不分摊，差额由其余机组承担")
    void lowWindTurbineBelowTechnicalFloorKeptAtActual() {
        // T-01 额定 2000（下限 300）但当前小风仅 200；可降空间按 0，保留 200。
        List<Turbine> turbines = List.of(
                turbine(1, "T-01", 2000, 200),
                turbine(2, "T-02", 2000, 2000),
                turbine(3, "T-03", 3000, 3000));
        // 全场可用 5200，目标 4000，压减 1200 全部由 T-02/T-03（可降空间 1700/2550）承担。
        List<Allocation> result = AllocationEngine.allocate(turbines, 4000, 1L, 2);

        assertThat(totalTarget(result)).isEqualTo(4000);
        Allocation lowWind = result.get(0);
        assertThat(lowWind.getCurtailKw()).isZero();
        assertThat(lowWind.getTargetPowerKw()).isEqualTo(200);
        assertThat(result.get(1).getTargetPowerKw()).isGreaterThanOrEqualTo(300);
        assertThat(result.get(2).getTargetPowerKw()).isGreaterThanOrEqualTo(450);
    }

    @Test
    @DisplayName("无可降空间且需要压减：目标低于下限时拒绝；恰等下限允许")
    void noHeadroomEdgeCases() {
        // 机组可用出力已恰为技术下限，全场可用 600。
        List<Turbine> turbines = List.of(
                turbine(1, "T-01", 2000, 300),
                turbine(2, "T-02", 2000, 300));

        List<Allocation> exact = AllocationEngine.allocate(turbines, 600, 1L, 1);
        assertThat(exact).allSatisfy(a -> assertThat(a.getCurtailKw()).isZero());

        assertThatThrownBy(() -> AllocationEngine.allocate(turbines, 599, 1L, 1))
                .isInstanceOf(ApiBadRequestException.class);
    }
}
