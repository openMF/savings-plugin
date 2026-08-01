package org.apache.fineract.fastpayment.sinpe.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;

@Entity
@Table(name = "m_sinpe_enrollment")
public class SinpeEnrollment extends AbstractPersistableCustom<Long> {

  @Column(name = "client_id", nullable = false)
  private Long clientId;

  @Column(name = "mobile_number", length = 20, nullable = false)
  private String mobileNumber;

  @Column(name = "is_verified", nullable = false)
  private boolean verified = false;

  @Column(name = "verified_on")
  private LocalDateTime verifiedOn;

  @Column(name = "pending_otp", length = 10)
  private String pendingOtp;

  @Column(name = "otp_expiry")
  private LocalDateTime otpExpiry;

  @Column(name = "created_by")
  private Long createdBy;

  @Column(name = "last_modified_by")
  private Long lastModifiedBy;

  protected SinpeEnrollment() {}

  public SinpeEnrollment(Long clientId, String mobileNumber, Long createdBy) {
    this.clientId = clientId;
    this.mobileNumber = mobileNumber;
    this.createdBy = createdBy;
  }

  public void setPendingOtp(String otp, LocalDateTime expiry) {
    this.pendingOtp = otp;
    this.otpExpiry = expiry;
  }

  public void clearPendingOtp() {
    this.pendingOtp = null;
    this.otpExpiry = null;
  }

  public void markAsVerified(LocalDateTime when) {
    this.verified = true;
    this.verifiedOn = when;
    clearPendingOtp();
  }

  public boolean isOtpValid(String otp, LocalDateTime now) {
    return pendingOtp != null
        && pendingOtp.equals(otp)
        && otpExpiry != null
        && !otpExpiry.isBefore(now);
  }

  // getters
  public Long getClientId() { return clientId; }
  public String getMobileNumber() { return mobileNumber; }
  public boolean isVerified() { return verified; }
  public LocalDateTime getVerifiedOn() { return verifiedOn; }
  public String getPendingOtp() { return pendingOtp; }
  public LocalDateTime getOtpExpiry() { return otpExpiry; }
}