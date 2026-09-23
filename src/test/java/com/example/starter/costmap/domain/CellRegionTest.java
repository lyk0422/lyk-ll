package com.example.starter.costmap.domain;

import com.example.starter.costmap.web.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 区域几何与校验测试。
 */
class CellRegionTest {

    @Test
    void areaContainsIntersects() {
        CellRegion r = new CellRegion(1, 2, 3, 4);
        assertThat(r.area()).isEqualTo(9);
        assertThat(r.contains(1, 2)).isTrue();
        assertThat(r.contains(3, 4)).isTrue();
        assertThat(r.contains(0, 2)).isFalse();
        assertThat(r.intersects(new CellRegion(3, 4, 5, 5))).isTrue();
        assertThat(r.intersects(new CellRegion(4, 2, 5, 4))).isFalse();
    }

    @Test
    void validateRejectsReversedAndOutOfBounds() {
        assertThatThrownBy(() -> new CellRegion(2, 0, 1, 1).validateWithin(5, 5))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new CellRegion(-1, 0, 1, 1).validateWithin(5, 5))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new CellRegion(0, 0, 5, 1).validateWithin(5, 5))
                .isInstanceOf(ApiException.class);
        assertThat(new CellRegion(0, 0, 4, 4).validateWithin(5, 5).area()).isEqualTo(25);
    }

    @Test
    void expandClipsToMap() {
        CellRegion r = new CellRegion(0, 0, 1, 1).expandAndClip(3, 6, 6);
        assertThat(r).isEqualTo(new CellRegion(0, 0, 4, 4));
        CellRegion corner = new CellRegion(4, 4, 5, 5).expandAndClip(3, 6, 6);
        assertThat(corner).isEqualTo(new CellRegion(1, 1, 5, 5));
    }

    @Test
    void unionBoundingBox() {
        assertThat(new CellRegion(0, 0, 1, 1).union(new CellRegion(4, 3, 5, 5)))
                .isEqualTo(new CellRegion(0, 0, 5, 5));
    }
}
