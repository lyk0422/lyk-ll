package com.example.starter.metro.fare;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.starter.metro.network.MileageRepository.LineMileage;

/**
 * 跨线收入分摊：按各线路承运里程比例分摊，比例保留四位小数，
 * 分摊金额取整到分且合计等于实收金额，差额归入里程最长线路。
 */
@Service
public class FareSplitter {

    /** 单条线路的分摊结果。 */
    public record Allocation(String lineCode, int distanceKm, BigDecimal ratio, long amountFen) {
    }

    /**
     * 分摊金额到各线路。
     *
     * @param amountFen 实收金额（分）
     * @param lines     各线路承运里程，须按里程降序、线路编码升序排列（最长线路在首位）
     * @return 各线路分摊结果，合计等于 amountFen
     */
    public List<Allocation> split(long amountFen, List<LineMileage> lines) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("lines must not be empty");
        }
        int totalKm = lines.stream().mapToInt(LineMileage::distanceKm).sum();
        if (totalKm <= 0) {
            // 同站进出等零里程场景：全额归入首条线路，比例 1.0000
            LineMileage only = lines.get(0);
            return List.of(new Allocation(only.lineCode(), only.distanceKm(),
                    new BigDecimal("1.0000"), amountFen));
        }
        List<Allocation> allocations = new ArrayList<>();
        long allocated = 0L;
        for (LineMileage line : lines) {
            BigDecimal ratio = BigDecimal.valueOf(line.distanceKm())
                    .divide(BigDecimal.valueOf(totalKm), 4, RoundingMode.HALF_UP);
            long amount = BigDecimal.valueOf(amountFen)
                    .multiply(BigDecimal.valueOf(line.distanceKm()))
                    .divide(BigDecimal.valueOf(totalKm), 0, RoundingMode.HALF_UP)
                    .longValueExact();
            allocations.add(new Allocation(line.lineCode(), line.distanceKm(), ratio, amount));
            allocated += amount;
        }
        long diff = amountFen - allocated;
        if (diff != 0) {
            Allocation longest = allocations.get(0);
            allocations.set(0, new Allocation(longest.lineCode(), longest.distanceKm(),
                    longest.ratio(), longest.amountFen() + diff));
        }
        return allocations;
    }
}
