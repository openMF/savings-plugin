/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.office.data.OfficeAddressData;
import org.apache.fineract.office.data.OfficeGeolocationData;
import org.apache.fineract.office.data.OfficeServiceData;
import org.apache.fineract.office.data.OfficeWorkingHoursData;
import org.apache.fineract.office.service.OfficeExtensionReadPlatformService;
import org.apache.fineract.office.service.OfficeExtensionWritePlatformService;
import org.springframework.stereotype.Component;

/**
 * JAX-RS resource exposing admin CRUD endpoints for office services and geolocation under {@code
 * /v2/offices/{officeId}/services} and {@code /v2/offices/{officeId}/geolocation}.
 */
@Path("/v2/offices")
@Component
@Tag(
    name = "Office Extensions",
    description =
        "Admin endpoints for managing office services and geolocation data. These endpoints populate"
            + " the data consumed by the self-service office endpoints in the selfservice-plugin.")
@RequiredArgsConstructor
public class OfficeExtensionApiResource {

  private static final String RESOURCE_NAME_FOR_PERMISSIONS = "OFFICE";

  private final PlatformSecurityContext context;
  private final OfficeExtensionReadPlatformService readService;
  private final OfficeExtensionWritePlatformService writeService;
  private final DefaultToApiJsonSerializer<OfficeServiceData> serviceSerializer;
  private final DefaultToApiJsonSerializer<OfficeGeolocationData> geolocationSerializer;
  private final DefaultToApiJsonSerializer<OfficeWorkingHoursData> workingHoursSerializer;

  /**
   * Lists all services associated with the given office.
   *
   * @param officeId the office identifier
   * @return JSON array of office service data
   */
  @GET
  @Path("{officeId}/services")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "List Office Services",
      description = "Returns all services configured for the specified office.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              array = @ArraySchema(schema = @Schema(implementation = OfficeServiceData.class))))
  public String retrieveOfficeServices(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId) {
    this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final Collection<OfficeServiceData> data = readService.retrieveOfficeServices(officeId);
    return serviceSerializer.serializeResult(data);
  }

  /**
   * Creates a new service for the given office.
   *
   * @param officeId the office identifier
   * @param jsonBody the JSON request body
   * @return JSON with the created service id
   */
  @POST
  @Path("{officeId}/services")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Create Office Service",
      description = "Adds a new service to the specified office.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String createOfficeService(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId,
      @Parameter(hidden = true) final String jsonBody) {
    this.context.authenticatedUser().validateHasCreatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final CommandProcessingResult result = writeService.createOfficeService(officeId, jsonBody);
    return serviceSerializer.serializeResult(result);
  }

  /**
   * Updates an existing office service.
   *
   * @param officeId the office identifier
   * @param serviceId the service identifier
   * @param jsonBody the JSON request body
   * @return JSON with the changes applied
   */
  @PUT
  @Path("{officeId}/services/{serviceId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Update Office Service",
      description = "Updates a specific service for the office.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String updateOfficeService(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId,
      @PathParam("serviceId") @Parameter(description = "serviceId") final Long serviceId,
      @Parameter(hidden = true) final String jsonBody) {
    this.context.authenticatedUser().validateHasUpdatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final CommandProcessingResult result =
        writeService.updateOfficeService(officeId, serviceId, jsonBody);
    return serviceSerializer.serializeResult(result);
  }

  /**
   * Deletes an office service.
   *
   * @param officeId the office identifier
   * @param serviceId the service identifier
   * @return JSON with the deleted service id
   */
  @DELETE
  @Path("{officeId}/services/{serviceId}")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Delete Office Service",
      description = "Removes a specific service from the office.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String deleteOfficeService(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId,
      @PathParam("serviceId") @Parameter(description = "serviceId") final Long serviceId) {
    this.context.authenticatedUser().validateHasDeletePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final CommandProcessingResult result = writeService.deleteOfficeService(officeId, serviceId);
    return serviceSerializer.serializeResult(result);
  }

  /**
   * Lists all addresses associated with the given office.
   *
   * @param officeId the office identifier
   * @return JSON array of office addresses data
   */
  @GET
  @Path("{officeId}/addresses")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "List Office Addresses",
      description = "Returns all Addresses configured for the specified office.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(
              array = @ArraySchema(schema = @Schema(implementation = OfficeServiceData.class))))
  public String retrieveOfficeAddresses(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId) {
    this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final Collection<OfficeAddressData> data = readService.retrieveOfficeAddresses(officeId);
    return serviceSerializer.serializeResult(data);
  }

  /**
   * Creates a new Address for the given office.
   *
   * @param officeId the office identifier
   * @param jsonBody the JSON request body
   * @return JSON with the created Address id
   */
  @POST
  @Path("{officeId}/addresses")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Create Office Address",
      description = "Adds a new Address to the specified office.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String createOfficeAddress(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId,
      @Parameter(hidden = true) final String jsonBody) {
    this.context.authenticatedUser().validateHasCreatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final CommandProcessingResult result = writeService.createOfficeAddress(officeId, jsonBody);
    return serviceSerializer.serializeResult(result);
  }

  /**
   * Updates an existing office Address.
   *
   * @param officeId the office identifier
   * @param serviceId the Address identifier
   * @param jsonBody the JSON request body
   * @return JSON with the changes applied
   */
  @PUT
  @Path("{officeId}/addresses/{addressId}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Update Office Address",
      description = "Updates a specific Address for the office.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String updateOfficeAddress(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId,
      @PathParam("addressId") @Parameter(description = "addressId") final Long addressId,
      @Parameter(hidden = true) final String jsonBody) {
    this.context.authenticatedUser().validateHasUpdatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final CommandProcessingResult result =
        writeService.updateOfficeAddress(officeId, addressId, jsonBody);
    return serviceSerializer.serializeResult(result);
  }

  /**
   * Deletes an office address.
   *
   * @param officeId the office identifier
   * @param serviceId the address identifier
   * @return JSON with the deleted service id
   */
  @DELETE
  @Path("{officeId}/addresses/{addressId}")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Delete Office Address",
      description = "Removes a specific Address from the office.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String deleteOfficeAddress(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId,
      @PathParam("addressId") @Parameter(description = "addressId") final Long addressId) {
    this.context.authenticatedUser().validateHasDeletePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final CommandProcessingResult result = writeService.deleteOfficeAddress(officeId, addressId);
    return serviceSerializer.serializeResult(result);
  }

  /**
   * Retrieves the geolocation for the given office.
   *
   * @param officeId the office identifier
   * @return JSON representation of geolocation data
   */
  @GET
  @Path("{officeId}/geolocation")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Office Geolocation",
      description = "Returns the latitude and longitude of the specified office.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content = @Content(schema = @Schema(implementation = OfficeGeolocationData.class)))
  public String retrieveOfficeGeolocation(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId) {
    this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final OfficeGeolocationData data = readService.retrieveOfficeGeolocation(officeId);
    return geolocationSerializer.serializeResult(data);
  }

  /**
   * Creates or updates the geolocation for the given office.
   *
   * @param officeId the office identifier
   * @param jsonBody the JSON request body containing latitude and longitude
   * @return JSON with the geolocation record id
   */
  @PUT
  @Path("{officeId}/geolocation")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Save Office Geolocation",
      description = "Creates or updates the geolocation for the office (1:1 relationship).")
  @ApiResponse(responseCode = "200", description = "OK")
  public String saveOfficeGeolocation(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId,
      @Parameter(hidden = true) final String jsonBody) {
    this.context.authenticatedUser().validateHasUpdatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final CommandProcessingResult result = writeService.saveOfficeGeolocation(officeId, jsonBody);
    return geolocationSerializer.serializeResult(result);
  }

  /**
   * Deletes the geolocation for the given office.
   *
   * @param officeId the office identifier
   * @return JSON with the deleted office id
   */
  @DELETE
  @Path("{officeId}/geolocation")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Delete Office Geolocation",
      description = "Removes the geolocation data for the office.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String deleteOfficeGeolocation(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId) {
    this.context.authenticatedUser().validateHasDeletePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final CommandProcessingResult result = writeService.deleteOfficeGeolocation(officeId);
    return geolocationSerializer.serializeResult(result);
  }

  /**
   * Retrieves the weekly working-hours schedule for the given office.
   *
   * @param officeId the office identifier
   * @return JSON representation of the office's weekly schedule
   */
  @GET
  @Path("{officeId}/schedules")
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Retrieve Office Working Hours",
      description = "Returns the single weekly working-hours schedule for the specified office.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content = @Content(schema = @Schema(implementation = OfficeWorkingHoursData.class)))
  public String retrieveOfficeWorkingHours(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId) {
    this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final OfficeWorkingHoursData data = readService.retrieveOfficeWorkingHours(officeId);
    return workingHoursSerializer.serializeResult(data);
  }

  /**
   * Creates or replaces the weekly working-hours schedule for the given office.
   *
   * @param officeId the office identifier
   * @param jsonBody the JSON request body containing the seven weekday rows
   * @return JSON with the office id and schedule changes
   */
  @PUT
  @Path("{officeId}/schedules")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @Operation(
      summary = "Save Office Working Hours",
      description = "Creates or replaces the single weekly working-hours schedule for the office.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String saveOfficeWorkingHours(
      @PathParam("officeId") @Parameter(description = "officeId") final Long officeId,
      @Parameter(hidden = true) final String jsonBody) {
    this.context.authenticatedUser().validateHasUpdatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final CommandProcessingResult result = writeService.saveOfficeWorkingHours(officeId, jsonBody);
    return workingHoursSerializer.serializeResult(result);
  }
}
