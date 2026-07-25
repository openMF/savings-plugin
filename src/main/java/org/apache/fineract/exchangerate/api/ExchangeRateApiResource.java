/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.api;

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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.exchangerate.data.CurrencyConversionData;
import org.apache.fineract.exchangerate.data.ExchangeRateData;
import org.apache.fineract.exchangerate.service.ExchangeRateReadPlatformService;
import org.apache.fineract.exchangerate.service.ExchangeRateWritePlatformService;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/** REST resource for administrator-configured exchange rates and currency conversion. */
@Path("/exchange-rates")
@Component
@Tag(
    name = "Dynamic Exchange Rates",
    description = "Configurable exchange rates and dynamic currency conversion.")
@RequiredArgsConstructor
public class ExchangeRateApiResource {

  private static final String RESOURCE_NAME_FOR_PERMISSIONS = "EXCHANGE_RATE";
  private static final String CONVERT_PERMISSION = "CONVERT_CURRENCY";
  private static final String READ_EXCHANGE_RATE_PERMISSION =
      "hasAuthority('ALL_FUNCTIONS') or hasAuthority('READ_EXCHANGE_RATE')";
  private static final String CREATE_EXCHANGE_RATE_PERMISSION =
      "hasAuthority('ALL_FUNCTIONS') or hasAuthority('CREATE_EXCHANGE_RATE')";
  private static final String UPDATE_EXCHANGE_RATE_PERMISSION =
      "hasAuthority('ALL_FUNCTIONS') or hasAuthority('UPDATE_EXCHANGE_RATE')";
  private static final String DELETE_EXCHANGE_RATE_PERMISSION =
      "hasAuthority('ALL_FUNCTIONS') or hasAuthority('DELETE_EXCHANGE_RATE')";
  private static final String CONVERT_CURRENCY_PERMISSION =
      "hasAuthority('ALL_FUNCTIONS') or hasAuthority('CONVERT_CURRENCY')";

  private final PlatformSecurityContext context;
  private final ExchangeRateReadPlatformService readService;
  private final ExchangeRateWritePlatformService writeService;
  private final DefaultToApiJsonSerializer<ExchangeRateData> exchangeRateSerializer;
  private final DefaultToApiJsonSerializer<CurrencyConversionData> conversionSerializer;

  @GET
  @Produces({MediaType.APPLICATION_JSON})
  @PreAuthorize(READ_EXCHANGE_RATE_PERMISSION)
  @Operation(
      summary = "List Exchange Rates",
      description =
          "Returns configured exchange rates, optionally filtered by currency pair, active state, or effective date.")
  @ApiResponse(
      responseCode = "200",
      description = "OK",
      content =
          @Content(array = @ArraySchema(schema = @Schema(implementation = ExchangeRateData.class))))
  public String retrieveExchangeRates(
      @QueryParam("sourceCurrency") @Parameter(description = "Source currency code")
          final String sourceCurrency,
      @QueryParam("targetCurrency") @Parameter(description = "Target currency code")
          final String targetCurrency,
      @QueryParam("active") @Parameter(description = "Active flag") final Boolean active,
      @QueryParam("effectiveOn") @Parameter(description = "Effective date")
          final String effectiveOn) {
    this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final Collection<ExchangeRateData> data =
        this.readService.retrieveAll(
            sourceCurrency, targetCurrency, active, parseEffectiveOn(effectiveOn));
    return this.exchangeRateSerializer.serializeResult(data);
  }

  @GET
  @Path("{id}")
  @Produces({MediaType.APPLICATION_JSON})
  @PreAuthorize(READ_EXCHANGE_RATE_PERMISSION)
  @Operation(
      summary = "Get Exchange Rate",
      description = "Returns a configured exchange rate by id.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String retrieveExchangeRate(
      @PathParam("id") @Parameter(description = "Exchange rate id") final Long id) {
    this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final ExchangeRateData data = this.readService.retrieveOne(id);
    return this.exchangeRateSerializer.serializeResult(data);
  }

  @POST
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @PreAuthorize(CREATE_EXCHANGE_RATE_PERMISSION)
  @Operation(summary = "Create Exchange Rate", description = "Creates a configured exchange rate.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String createExchangeRate(@Parameter(hidden = true) final String jsonBody) {
    this.context.authenticatedUser().validateHasCreatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final CommandProcessingResult result = this.writeService.createExchangeRate(jsonBody);
    return this.exchangeRateSerializer.serializeResult(result);
  }

  @PUT
  @Path("{id}")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @PreAuthorize(UPDATE_EXCHANGE_RATE_PERMISSION)
  @Operation(summary = "Update Exchange Rate", description = "Updates a configured exchange rate.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String updateExchangeRate(
      @PathParam("id") @Parameter(description = "Exchange rate id") final Long id,
      @Parameter(hidden = true) final String jsonBody) {
    this.context.authenticatedUser().validateHasUpdatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final CommandProcessingResult result = this.writeService.updateExchangeRate(id, jsonBody);
    return this.exchangeRateSerializer.serializeResult(result);
  }

  @DELETE
  @Path("{id}")
  @Produces({MediaType.APPLICATION_JSON})
  @PreAuthorize(DELETE_EXCHANGE_RATE_PERMISSION)
  @Operation(summary = "Delete Exchange Rate", description = "Deletes a configured exchange rate.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String deleteExchangeRate(
      @PathParam("id") @Parameter(description = "Exchange rate id") final Long id) {
    this.context.authenticatedUser().validateHasDeletePermission(RESOURCE_NAME_FOR_PERMISSIONS);
    final CommandProcessingResult result = this.writeService.deleteExchangeRate(id);
    return this.exchangeRateSerializer.serializeResult(result);
  }

  @POST
  @Path("convert")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  @PreAuthorize(CONVERT_CURRENCY_PERMISSION)
  @Operation(
      summary = "Convert Currency",
      description = "Converts an amount using the active exchange rate for the requested date.")
  @ApiResponse(responseCode = "200", description = "OK")
  public String convert(@Parameter(hidden = true) final String jsonBody) {
    this.context.authenticatedUser().validateHasPermissionTo(CONVERT_PERMISSION);
    final CurrencyConversionData result = this.readService.convert(jsonBody);
    return this.conversionSerializer.serializeResult(result);
  }

  private LocalDate parseEffectiveOn(final String effectiveOn) {
    if (effectiveOn == null || effectiveOn.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(effectiveOn);
    } catch (final DateTimeParseException e) {
      final ApiParameterError error =
          ApiParameterError.parameterError(
              "validation.msg.exchangeRate.date.invalid",
              "The parameter effectiveOn must be a valid ISO date.",
              "effectiveOn",
              effectiveOn);
      throw new PlatformApiDataValidationException(List.of(error), e);
    }
  }
}
