package com.example.starter.repo;

import com.example.starter.web.dto.CarDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 编组车辆数据访问。
 */
@Repository
public class TrainCarRepository {

    private static final RowMapper<CarRow> ROW_MAPPER = (rs, n) -> new CarRow(
            rs.getLong("id"),
            rs.getLong("job_id"),
            rs.getString("car_no"),
            rs.getInt("seq_no"),
            rs.getBigDecimal("weight_tons"),
            rs.getBigDecimal("length"),
            rs.getBoolean("valve_closed"),
            rs.getBoolean("detached"));

    private final JdbcTemplate jdbc;

    public TrainCarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加车辆，顺位为指定起始值。
     */
    public void insert(long jobId, int seqNo, CarDto car) {
        jdbc.update("INSERT INTO train_car (job_id, car_no, seq_no, weight_tons, length, valve_closed,"
                        + " detached, created_at) VALUES (?, ?, ?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)",
                jobId, car.carNo(), seqNo, car.weightTons(), car.length(), car.valveClosed());
    }

    /**
     * 查询在列车辆（未摘下），按顺位排序。
     */
    public List<CarRow> findActiveByJobId(long jobId) {
        return jdbc.query("SELECT * FROM train_car WHERE job_id = ? AND detached = FALSE ORDER BY seq_no, id",
                ROW_MAPPER, jobId);
    }

    /**
     * 摘下指定车辆；返回是否命中在列车辆。
     */
    public boolean detach(long jobId, String carNo) {
        return jdbc.update("UPDATE train_car SET detached = TRUE"
                + " WHERE job_id = ? AND car_no = ? AND detached = FALSE", jobId, carNo) > 0;
    }

    /**
     * 查询指定车辆（含已摘下）。
     */
    public List<CarRow> findByJobIdAndCarNo(long jobId, String carNo) {
        return jdbc.query("SELECT * FROM train_car WHERE job_id = ? AND car_no = ?",
                ROW_MAPPER, jobId, carNo);
    }

    /**
     * 重新挂上已摘下的同号车辆。
     */
    public void reattach(long jobId, String carNo, int seqNo) {
        jdbc.update("UPDATE train_car SET detached = FALSE, seq_no = ?"
                + " WHERE job_id = ? AND car_no = ?", seqNo, jobId, carNo);
    }
}
