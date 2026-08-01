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
import lombok.RequiredArgsConstructor;
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
    description = "Backoffice endpoints to enroll and manage SINPE Móvil phone numbers for clients.")
@RequiredArgsConstructor
public class SinpeEnrollmentApiResource {

  private final PlatformSecurityContext context;
  private final DefaultToApiJsonSerializer<CommandProcessingResult> toApiJsonSerializer;
  private final SinpeEnrollmentWritePlatformService writePlatformService;
  private final SinpeEnrollmentReadPlatformService readPlatformService;
  private final DefaultToApiJsonSerializer<SinpePhoneStatusData> phoneStatusSerializer;

  @POST
  @Path("/request")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Request SINPE Móvil Enrollment",
      description = "Generates an OTP for the given client and phone number.")
  public String requestEnrollment(final String apiRequestBodyAsJson) {
    context.authenticatedUser().validateHasPermissionTo("CREATE_SINPE_ENROLLMENT");

    JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    Long clientId = json.get("clientId").getAsLong();
    String mobileNumber = json.get("mobileNumber").getAsString();

    CommandProcessingResult result = writePlatformService.requestEnrollment(clientId, mobileNumber);
    return toApiJsonSerializer.serialize(result);
  }

  @POST
  @Path("/confirm")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Confirm SINPE Móvil Enrollment",
      description = "Verifies the OTP and marks the phone number as verified for the client.")
  public String confirmEnrollment(final String apiRequestBodyAsJson) {
    context.authenticatedUser().validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");

    JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    Long clientId = json.get("clientId").getAsLong();
    String mobileNumber = json.get("mobileNumber").getAsString();
    String otp = json.get("otp").getAsString();

    CommandProcessingResult result =
        writePlatformService.confirmEnrollment(clientId, mobileNumber, otp);
    return toApiJsonSerializer.serialize(result);
  }

  @POST
    @Path("/subscription")
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces({MediaType.APPLICATION_JSON})
    @Operation(
        summary = "Create SINPE Subscription",
        description = "Creates a new SINPE subscription. Only clientId, phoneNumber, iban and otp are required.")
    public String createSubscription(final String apiRequestBodyAsJson) {
      context.authenticatedUser().validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");

      JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();

      if (!json.has("clientId") || json.get("clientId").isJsonNull()) {
        throw new GeneralPlatformDomainRuleException(
            "error.msg.sinpe.clientId.required", "clientId is required.");
      }
      if (!json.has("phoneNumber") || json.get("phoneNumber").isJsonNull()
          || json.get("phoneNumber").getAsString().isBlank()) {
        throw new GeneralPlatformDomainRuleException(
            "error.msg.sinpe.phoneNumber.required", "phoneNumber is required.");
      }
      if (!json.has("iban") || json.get("iban").isJsonNull()
          || json.get("iban").getAsString().isBlank()) {
        throw new GeneralPlatformDomainRuleException(
            "error.msg.sinpe.iban.required", "iban is required.");
      }
      if (!json.has("otp") || json.get("otp").isJsonNull()
          || json.get("otp").getAsString().isBlank()) {
        throw new GeneralPlatformDomainRuleException(
            "error.msg.sinpe.otp.required", "OTP is required for this operation.");
      }

      Long clientId = json.get("clientId").getAsLong();
      String phoneNumber = json.get("phoneNumber").getAsString();
      String iban = json.get("iban").getAsString();
      String otp = json.get("otp").getAsString();

      CommandProcessingResult result =
          writePlatformService.createSubscription(clientId, phoneNumber, iban, otp);
      return toApiJsonSerializer.serialize(result);
    }

  @POST
  @Path("/subscription/edit")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Edit SINPE Subscription",
      description = "Edits an existing SINPE subscription. Requires a valid OTP.")
  public String editSubscription(final String apiRequestBodyAsJson) {
    context.authenticatedUser().validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");

    JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    Long clientId = json.get("clientId").getAsLong();
    String otp = json.get("otp").getAsString();
    json.remove("otp");
    json.remove("clientId");

    SinpeSubscriptionEditRequest request =
        new com.google.gson.Gson().fromJson(json.toString(), SinpeSubscriptionEditRequest.class);
    CommandProcessingResult result =
        writePlatformService.editSubscription(clientId, request, otp);
    return toApiJsonSerializer.serialize(result);
  }

  @DELETE
    @Path("/subscription/{phoneNumber}")
    @Consumes({MediaType.APPLICATION_JSON})
    @Produces({MediaType.APPLICATION_JSON})
    @Operation(
        summary = "Delete SINPE Subscription",
        description = "Deletes a SINPE subscription. Requires a valid OTP in the request body.")
    public String deleteSubscription(
        @PathParam("phoneNumber") final String phoneNumber,
        final String apiRequestBodyAsJson) {

      context.authenticatedUser().validateHasPermissionTo("DELETE_SINPE_ENROLLMENT");

      JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();

      if (!json.has("clientId") || json.get("clientId").isJsonNull()) {
        throw new GeneralPlatformDomainRuleException(
            "error.msg.sinpe.clientId.required", "clientId is required.");
      }
      if (!json.has("otp") || json.get("otp").isJsonNull() || json.get("otp").getAsString().isBlank()) {
        throw new GeneralPlatformDomainRuleException(
            "error.msg.sinpe.otp.required", "OTP is required for this operation.");
      }

      Long clientId = json.get("clientId").getAsLong();
      String otp = json.get("otp").getAsString();

      CommandProcessingResult result =
          writePlatformService.deleteSubscription(clientId, phoneNumber, otp);
      return toApiJsonSerializer.serialize(result);
    }
    
    @GET
    @Path("/phone/{phoneNumber}")
    @Produces({MediaType.APPLICATION_JSON})
    @Operation(
        summary = "Get SINPE Móvil phone status",
        description = "Retrieves the current status of a phone number from the external SINPE system.")
    public String getPhoneStatus(@PathParam("phoneNumber") final String phoneNumber) {
      context.authenticatedUser().validateHasPermissionTo("READ_SINPE_ENROLLMENT");

      SinpePhoneStatusData data = readPlatformService.retrievePhoneStatus(phoneNumber);
      return phoneStatusSerializer.serialize(data);
    }
}