package org.apache.fineract.fastpayment.sinpe.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SinpeEnrollmentRepository
    extends JpaRepository<SinpeEnrollment, Long> {

  /**
   * Finds the enrollment row for a client and phone number.
   *
   * @param clientId client identifier
   * @param mobileNumber phone number used for enrollment
   * @return matching enrollment, if present
   */
  Optional<SinpeEnrollment> findByClientIdAndMobileNumber(
      Long clientId, String mobileNumber);

  /**
   * Finds a verified enrollment row for a client and phone number.
   *
   * @param clientId client identifier
   * @param mobileNumber phone number used for enrollment
   * @return matching verified enrollment, if present
   */
  Optional<SinpeEnrollment> findByClientIdAndMobileNumberAndVerifiedTrue(
      Long clientId, String mobileNumber);

  /**
   * Finds an enrollment by phone number and local link status.
   *
   * @param mobileNumber phone number used for enrollment
   * @param status local link status
   * @return matching enrollment, if present
   */
  Optional<SinpeEnrollment> findFirstByMobileNumberAndStatus(
      String mobileNumber, String status);

  /**
   * Finds an enrollment by savings account and local link status.
   *
   * @param savingsAccountId savings account identifier
   * @param status local link status
   * @return matching enrollment, if present
   */
  Optional<SinpeEnrollment> findFirstBySavingsAccountIdAndStatus(
      Long savingsAccountId, String status);

  /**
   * Finds active enrollment rows for a savings account and local link status.
   *
   * @param savingsAccountId savings account identifier
   * @param status local link status
   * @return matching enrollments
   */
  List<SinpeEnrollment> findBySavingsAccountIdAndStatusOrderByMobileNumberAsc(
      Long savingsAccountId, String status);
}
