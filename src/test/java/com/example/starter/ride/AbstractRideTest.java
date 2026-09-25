package com.example.starter.ride;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.StarterApplication;
import com.example.starter.ride.RideTestConfiguration.H2DatabaseReleaser;
import com.example.starter.ride.domain.Ride;
import com.example.starter.ride.repo.ChecklistRepository;
import com.example.starter.ride.repo.RideRepository;

/**
 * 测试基类：场景间清理业务表并重新播种合成设备与清单，消除顺序依赖。
 */
@SpringBootTest(classes = {StarterApplication.class, RideTestConfiguration.class})
public abstract class AbstractRideTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected RideRepository rideRepository;

    @Autowired
    protected ChecklistRepository checklistRepository;

    @Autowired
    protected H2DatabaseReleaser releaser;

    protected void resetAndSeed(String... rideIds) {
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("DELETE FROM operation_reading");
        jdbcTemplate.update("DELETE FROM open_event");
        jdbcTemplate.update("DELETE FROM trial_run");
        jdbcTemplate.update("DELETE FROM daily_check_item_result");
        jdbcTemplate.update("DELETE FROM daily_check_record");
        jdbcTemplate.update("DELETE FROM ride_status_event");
        jdbcTemplate.update("DELETE FROM checklist_item");
        jdbcTemplate.update("DELETE FROM ride");
        if (rideIds.length == 0) {
            seedRide("R001", 800.0d, 20.0d);
        } else {
            for (String rideId : rideIds) {
                seedRide(rideId, 800.0d, 20.0d);
            }
        }
    }

    protected void seedRide(String rideId, double loadThreshold, double speedThreshold) {
        Ride ride = new Ride();
        ride.setId(rideId);
        ride.setName("合成设备-" + rideId);
        ride.setLoadThreshold(loadThreshold);
        ride.setSpeedThreshold(speedThreshold);
        ride.setStatus(RideStatus.PENDING_DAILY_CHECK.name());
        ride.setDailyCheckPassed(false);
        ride.setTrialPassedCount(0);
        ride.setReopenCount(0);
        ride.setOpenSeq(0);
        ride.setUpdatedAt(java.time.Instant.parse("2026-09-26T02:00:00Z"));
        rideRepository.insert(ride);
        checklistRepository.insertItem(rideId, "PRESSURE_BAR", "压杠装置");
        checklistRepository.insertItem(rideId, "SEAT_BELT", "安全带");
        checklistRepository.insertItem(rideId, "INTERLOCK", "安全联锁装置");
        checklistRepository.insertItem(rideId, "BRAKE", "制动系统");
        checklistRepository.insertItem(rideId, "EMERGENCY_STOP", "急停按钮");
    }

    protected Ride reload(String rideId) {
        return rideRepository.findById(rideId);
    }
}
