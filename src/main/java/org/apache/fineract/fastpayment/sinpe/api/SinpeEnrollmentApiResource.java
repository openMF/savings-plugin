package org.apache.fineract.fastpayment.sinpe.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionEditRequest;
import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionRequest;
import org.apache.fineract.fastpayment.sinpe.service.SinpeEnrollmentWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
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
      description = "Creates a new SINPE subscription in the external system. Requires a valid OTP.")
  public String createSubscription(final String apiRequestBodyAsJson) {
    context.authenticatedUser().validateHasPermissionTo("UPDATE_SINPE_ENROLLMENT");

    JsonObject json = JsonParser.parseString(apiRequestBodyAsJson).getAsJsonObject();
    Long clientId = json.get("clientId").getAsLong();
    String otp = json.get("otp").getAsString();
    json.remove("otp");
    json.remove("clientId");

    SinpeSubscriptionRequest request =
        new com.google.gson.Gson().fromJson(json.toString(), SinpeSubscriptionRequest.class);
    CommandProcessingResult result =
        writePlatformService.createSubscription(clientId, request, otp);
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
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Delete SINPE Subscription",
      description = "Deletes a SINPE subscription. Requires a valid OTP.")
  public String deleteSubscription(
      @PathParam("phoneNumber") final String phoneNumber,
      @QueryParam("clientId") final Long clientId,
      @QueryParam("otp") final String otp) {
    context.authenticatedUser().validateHasPermissionTo("DELETE_SINPE_ENROLLMENT");

    CommandProcessingResult result =
        writePlatformService.deleteSubscription(clientId, phoneNumber, otp);
    return toApiJsonSerializer.serialize(result);
  }
}