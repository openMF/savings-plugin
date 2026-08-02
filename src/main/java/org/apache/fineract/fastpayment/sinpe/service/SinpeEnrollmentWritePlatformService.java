package org.apache.fineract.fastpayment.sinpe.service;

import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionEditRequest;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;

public interface SinpeEnrollmentWritePlatformService {

  /**
   * Requests a phone OTP using the existing SINPE enrollment flow.
   *
   * @param clientId client requesting enrollment
   * @param mobileNumber phone number to enroll
   * @return command result for the enrollment request
   */
  CommandProcessingResult requestEnrollment(Long clientId,
      String mobileNumber);

  /**
   * Requests enrollment for exactly one supported identifier.
   *
   * @param clientId client requesting enrollment
   * @param mobileNumber phone number to enroll
   * @param emailAddress email identifier supplied by the caller
   * @return command result for the enrollment request
   */
  CommandProcessingResult requestEnrollment(Long clientId,
      String mobileNumber,
      String emailAddress);

  /**
   * Confirms a phone enrollment OTP.
   *
   * @param clientId client requesting enrollment
   * @param mobileNumber phone number being confirmed
   * @param otp submitted OTP
   * @return command result for the confirmation
   */
  CommandProcessingResult confirmEnrollment(Long clientId,
      String mobileNumber,
      String otp);

  /**
   * Creates a phone subscription through the existing provider client.
   *
   * @param clientId client owning the savings account
   * @param phoneNumber phone number to link
   * @param iban savings account IBAN/external ID
   * @param otp submitted OTP
   * @return command result for the subscription
   */
  CommandProcessingResult createSubscription(Long clientId,
      String phoneNumber,
      String iban,
      String otp);

  /**
   * Creates a subscription for exactly one supported identifier.
   *
   * @param clientId client owning the savings account
   * @param phoneNumber phone number to link
   * @param emailAddress email identifier supplied by the caller
   * @param iban savings account IBAN/external ID
   * @param otp submitted OTP
   * @return command result for the subscription
   */
  CommandProcessingResult createSubscription(
      Long clientId,
      String phoneNumber,
      String emailAddress,
      String iban,
      String otp);

  /**
   * Edits a phone subscription using the existing provider endpoint.
   *
   * @param clientId client owning the subscription
   * @param request edit request payload
   * @param otp submitted OTP
   * @return command result for the edit operation
   */
  CommandProcessingResult editSubscription(Long clientId,
      SinpeSubscriptionEditRequest request,
      String otp);

  /**
   * Deletes a phone subscription through the existing provider endpoint.
   *
   * @param clientId client owning the subscription
   * @param phoneNumber linked phone number
   * @param otp submitted OTP
   * @return command result for the delete operation
   */
  CommandProcessingResult deleteSubscription(Long clientId,
      String phoneNumber,
      String otp);
}
