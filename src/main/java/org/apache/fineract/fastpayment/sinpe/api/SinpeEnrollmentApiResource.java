package org.apache.fineract.fastpayment.sinpe.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.fastpayment.sinpe.data.SinpeLinkedPhoneData;
import org.apache.fineract.fastpayment.sinpe.data.SinpePhoneStatusData;
import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionEditRequest;
import org.apache.fineract.fastpayment.sinpe.service.SinpeEnrollmentReadPlatformService;
import org.apache.fineract.fastpayment.sinpe.service.SinpeEnrollmentWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v2/sinpe/enrollment")
@Component
@Tag(
    name = "SINPE Móvil Enrollment (Backoffice)",
    description = "Backoffice SINPE Móvil enrollment endpoints.")
@RequiredArgsConstructor
public final class SinpeEnrollmentApiResource {

  /** Security context used for SINPE enrollment permissions. */
  private final PlatformSecurityContext context;

  /** Serializer for platform command responses. */
  private final DefaultToApiJsonSerializer<CommandProcessingResult>
      toApiJsonSerializer;

  /** Write service for OTP, link, edit and delink operations. */
  private final SinpeEnrollmentWritePlatformService writePlatformService;

  /** Read service for external phone status lookup. */
  private final SinpeEnrollmentReadPlatformService readPlatformService;

  /** Serializer for phone status responses. */
  private final DefaultToApiJsonSerializer<SinpePhoneStatusData>
      phoneStatusSerializer;

  /** Serializer for linked phone responses. */
  private final DefaultToApiJsonSerializer<SinpeLinkedPhoneData>
      linkedPhoneSerializer;

  /**
   * Starts the existing SINPE phone OTP request flow.
   *
   * @param apiRequestBodyAsJson request JSON with client and identifier data
   * @return serialized command result
   */
  @POST
  @Path("/request")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Request SINPE Móvil Enrollment",
      description = "Generates an OTP for the given client and phone number.")
  public String requestEnrollment(final String apiRequestBodyAsJson) {
    context.authenticatedUser()
        .validateHasPermissionTo("CREATE_SINPE_ENROLLMENT");

    JsonObject json =
        JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    Long clientId = getRequiredLong(json, "clientId");
    String mobileNumber = getOptionalString(json, "mobileNumber");
    String emailAddress = getOptionalString(json, "emailAddress");

    CommandProcessingResult result =
        writePlatformService.requestEnrollment(
            clientId, mobileNumber, emailAddress);
    return toApiJsonSerializer.serialize(result);
  }

  /**
   * Confirms the existing SINPE phone OTP enrollment.
   *
   * @param apiRequestBodyAsJson request JSON containing client, phone and OTP
   * @return serialized command result
   */
  @POST
  @Path("/confirm")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Confirm SINPE Móvil Enrollment",
      description = "Verifies the OTP for the client phone number.")
  public String confirmEnrollment(final String apiRequestBodyAsJson) {
    context.authenticatedUser()
        .validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");

    JsonObject json =
        JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    Long clientId = getRequiredLong(json, "clientId");
    String mobileNumber = getRequiredString(json, "mobileNumber");
    String otp = getRequiredString(json, "otp");

    CommandProcessingResult result =
        writePlatformService.confirmEnrollment(clientId, mobileNumber, otp);
    return toApiJsonSerializer.serialize(result);
  }

  /**
   * Creates the provider subscription after validating the savings account.
   *
   * @param apiRequestBodyAsJson request JSON with link fields
   * @return serialized command result
   */
  @POST
  @Path("/subscription")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Create SINPE Subscription",
      description = "Creates a new SINPE subscription.")
  public String createSubscription(final String apiRequestBodyAsJson) {
    context.authenticatedUser()
        .validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");

    JsonObject json =
        JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();

    if (!json.has("clientId") || json.get("clientId").isJsonNull()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.clientId.required", "clientId is required.");
    }
    if (!json.has("iban")
        || json.get("iban").isJsonNull()
        || json.get("iban").getAsString().isBlank()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.iban.required", "iban is required.");
    }
    if (!json.has("otp")
        || json.get("otp").isJsonNull()
        || json.get("otp").getAsString().isBlank()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.otp.required",
          "OTP is required for this operation.");
    }

    Long clientId = getRequiredLong(json, "clientId");
    String phoneNumber = getOptionalString(json, "phoneNumber");
    String emailAddress = getOptionalString(json, "emailAddress");
    String iban = getRequiredString(json, "iban");
    String otp = getRequiredString(json, "otp");

    CommandProcessingResult result =
        writePlatformService.createSubscription(
            clientId, phoneNumber, emailAddress, iban, otp);
    return toApiJsonSerializer.serialize(result);
  }

  /**
   * Preserves the existing provider edit subscription operation.
   *
   * @param apiRequestBodyAsJson request JSON containing edit fields and OTP
   * @return serialized command result
   */
  @POST
  @Path("/subscription/edit")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Edit SINPE Subscription",
      description = "Edits an existing SINPE subscription.")
  public String editSubscription(final String apiRequestBodyAsJson) {
    context.authenticatedUser()
        .validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");

    JsonObject json =
        JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    Long clientId = getRequiredLong(json, "clientId");
    String otp = getRequiredString(json, "otp");
    json.remove("otp");
    json.remove("clientId");

    SinpeSubscriptionEditRequest request = new com.google.gson.Gson()
        .fromJson(json.toString(), SinpeSubscriptionEditRequest.class);
    CommandProcessingResult result =
        writePlatformService.editSubscription(clientId, request, otp);
    return toApiJsonSerializer.serialize(result);
  }

  /**
   * Deletes the provider subscription for a linked phone number.
   *
   * @param phoneNumber linked phone number path parameter
   * @param apiRequestBodyAsJson request JSON containing client and OTP
   * @return serialized command result
   */
  @DELETE
  @Path("/subscription/{phoneNumber}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Delete SINPE Subscription",
      description = "Deletes a SINPE subscription using a valid OTP.")
  public String deleteSubscription(
      @PathParam("phoneNumber") final String phoneNumber,
      final String apiRequestBodyAsJson) {

    context.authenticatedUser()
        .validateHasPermissionTo("DELETE_SINPE_ENROLLMENT");

    JsonObject json =
        JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();

    if (!json.has("clientId") || json.get("clientId").isJsonNull()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.clientId.required", "clientId is required.");
    }
    if (!json.has("otp")
        || json.get("otp").isJsonNull()
        || json.get("otp").getAsString().isBlank()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.sinpe.otp.required",
          "OTP is required for this operation.");
    }

    Long clientId = getRequiredLong(json, "clientId");
    String otp = getRequiredString(json, "otp");

    CommandProcessingResult result =
        writePlatformService.deleteSubscription(clientId, phoneNumber, otp);
    return toApiJsonSerializer.serialize(result);
  }

  /**
   * Retrieves the external provider status for a phone number.
   *
   * @param phoneNumber phone number path parameter
   * @return serialized phone status data
   */
  @GET
  @Path("/phone/{phoneNumber}")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Get SINPE Móvil phone status",
      description = "Retrieves phone status from the external SINPE system.")
  public String getPhoneStatus(
      @PathParam("phoneNumber") final String phoneNumber) {
    context.authenticatedUser()
        .validateHasPermissionTo("READ_SINPE_ENROLLMENT");

    SinpePhoneStatusData data =
        readPlatformService.retrievePhoneStatus(phoneNumber);
    return phoneStatusSerializer.serialize(data);
  }

  /**
   * Retrieves active local phone links for a savings account.
   *
   * @param savingsAccountId savings account identifier
   * @return serialized linked phone data
   */
  @GET
  @Path("/savingsaccounts/{savingsAccountId}/phones")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "List linked SINPE Móvil phones for savings account",
      description = "Returns active local SINPE phone links for a savings account.")
  public String retrieveLinkedPhones(
      @PathParam("savingsAccountId") final Long savingsAccountId) {
    context.authenticatedUser()
        .validateHasPermissionTo("READ_SINPE_ENROLLMENT");

    Collection<SinpeLinkedPhoneData> data =
        readPlatformService.retrieveLinkedPhones(savingsAccountId);
    return linkedPhoneSerializer.serializeResult(data);
  }

  private String getOptionalString(
      final JsonObject json,
      final String memberName) {
    if (!json.has(memberName) || json.get(memberName).isJsonNull()) {
      return null;
    }
    return StringUtils.trimToNull(json.get(memberName).getAsString());
  }

  private Long getRequiredLong(final JsonObject json, final String memberName) {
    if (!json.has(memberName) || json.get(memberName).isJsonNull()) {
      throw requiredField(memberName);
    }
    return json.get(memberName).getAsLong();
  }

  private String getRequiredString(
      final JsonObject json,
      final String memberName) {
    String value = getOptionalString(json, memberName);
    if (value == null) {
      throw requiredField(memberName);
    }
    return value;
  }

  private GeneralPlatformDomainRuleException requiredField(
      final String memberName) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.sinpe." + memberName + ".required",
        memberName + " is required.");
  }
}
