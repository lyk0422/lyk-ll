package com.example.starter.radio.service;

import java.time.Clock;

import com.example.starter.radio.model.MotherLiquor;
import com.example.starter.radio.model.Nuclide;
import com.example.starter.radio.model.Prescription;
import com.example.starter.radio.repository.CalibratorRepository;
import com.example.starter.radio.repository.MotherLiquorRepository;
import com.example.starter.radio.repository.NuclideRepository;
import com.example.starter.radio.repository.PrescriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基础台账登记：活度计、母液批次、处方。全部为合成数据。
 */
@Service
public class RegistrationService {

    private final CalibratorRepository calibrators;
    private final MotherLiquorRepository motherLiquors;
    private final PrescriptionRepository prescriptions;
    private final NuclideRepository nuclides;
    private final Clock clock;

    public RegistrationService(CalibratorRepository calibrators,
                               MotherLiquorRepository motherLiquors,
                               PrescriptionRepository prescriptions,
                               NuclideRepository nuclides,
                               Clock clock) {
        this.calibrators = calibrators;
        this.motherLiquors = motherLiquors;
        this.prescriptions = prescriptions;
        this.nuclides = nuclides;
        this.clock = clock;
    }

    @Transactional
    public long registerCalibrator(String code, String name) {
        return calibrators.insertCalibrator(code, name, clock.instant().getEpochSecond());
    }

    @Transactional
    public MotherLiquor registerMotherLiquor(String batchCode, String nuclideCode,
                                             double initialActivityBq, long referenceTimeEpoch) {
        Nuclide nuclide = nuclides.findByCode(nuclideCode)
                .orElseThrow(() -> new NotFoundException("核素不存在: " + nuclideCode));
        long id = motherLiquors.insert(batchCode, nuclide.id(), initialActivityBq,
                referenceTimeEpoch, clock.instant().getEpochSecond());
        return motherLiquors.findById(id).orElseThrow();
    }

    @Transactional
    public Prescription registerPrescription(String code, String nuclideCode,
                                             double prescribedActivityBq, long administrationTimeEpoch) {
        Nuclide nuclide = nuclides.findByCode(nuclideCode)
                .orElseThrow(() -> new NotFoundException("核素不存在: " + nuclideCode));
        long id = prescriptions.insert(code, nuclide.id(), prescribedActivityBq,
                administrationTimeEpoch, clock.instant().getEpochSecond());
        return prescriptions.findById(id).orElseThrow();
    }
}
