package org.apache.fineract.fastpayment.sinpe.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SinpeEnrollmentRepository extends JpaRepository<SinpeEnrollment, Long> {

  Optional<SinpeEnrollment> findByClientIdAndMobileNumber(Long clientId, String mobileNumber);

  Optional<SinpeEnrollment> findByClientIdAndMobileNumberAndVerifiedTrue(Long clientId, String mobileNumber);
}