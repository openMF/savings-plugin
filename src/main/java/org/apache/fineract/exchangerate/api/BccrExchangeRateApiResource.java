/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.exchangerate.domain.BccrExchangeRate;
import org.apache.fineract.exchangerate.service.BccrExchangeRateService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

/**
 * REST API resource for accessing BCCR exchange rate data.
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Getting the current/latest exchange rate</li>
 *   <li>Getting historical exchange rates</li>
 *   <li>Manually triggering a rate fetch</li>
 * </ul>
 */
@Path("/v2/exchange-rates")
@Component
@Tag(
    name = "BCCR Exchange Rates",
    description = "API for accessing exchange rates from the Central Bank of Costa Rica (BCCR)")
@RequiredArgsConstructor
@Slf4j
public class BccrExchangeRateApiResource {

  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

  private final BccrExchangeRateService exchangeRateService;
  private final PlatformSecurityContext context;
  private final Gson gson = new Gson();

  @GET
  @Path("/current")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Get Current Exchange Rate",
      description = "Returns the latest available USD/CRC exchange rate from BCCR.")
  public String getCurrentRate() {
    context.authenticatedUser().validateHasReadPermission("EXCHANGE_RATE");

    return exchangeRateService
        .getCurrentDailyRate()
        .map(gson::toJson)
        .orElse("{\"error\": \"No exchange rate available\"}");
  }

  @GET
  @Path("/latest")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Get Latest Exchange Rate",
      description = "Returns the most recent exchange rate with full details.")
  public String getLatestRate() {
    context.authenticatedUser().validateHasReadPermission("EXCHANGE_RATE");

    return exchangeRateService
        .getLatestRate()
        .map(this::rateToJson)
        .orElse("{\"error\": \"No exchange rate available\"}");
  }

  @GET
  @Path("/{date}")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Get Exchange Rate by Date",
      description = "Returns the exchange rate for a specific date (format: yyyy-MM-dd).")
  public String getRateByDate(
      @PathParam("date") @Parameter(description = "Date in yyyy-MM-dd format") String date) {
    context.authenticatedUser().validateHasReadPermission("EXCHANGE_RATE");

    LocalDate localDate = LocalDate.parse(date, DATE_FORMAT);
    return exchangeRateService
        .getRateForDate(localDate)
        .map(this::rateToJson)
        .orElse("{\"error\": \"No exchange rate available for date: " + date + "\"}");
  }

  @GET
  @Path("/historical")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Get Historical Exchange Rates",
      description = "Returns exchange rates for a date range.")
  public String getHistoricalRates(
      @QueryParam("from") @Parameter(description = "Start date (yyyy-MM-dd)") String from,
      @QueryParam("to") @Parameter(description = "End date (yyyy-MM-dd)") String to) {
    context.authenticatedUser().validateHasReadPermission("EXCHANGE_RATE");

    LocalDate fromDate = LocalDate.parse(from, DATE_FORMAT);
    LocalDate toDate = LocalDate.parse(to, DATE_FORMAT);

    List<BccrExchangeRate> rates = exchangeRateService.getHistoricalRates(fromDate, toDate);
    return gson.toJson(rates.stream().map(this::rateToJson).toList());
  }

  @POST
  @Path("/fetch")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Manually Fetch Exchange Rate",
      description = "Triggers a manual fetch of today's exchange rate from BCCR. Requires ADMIN permission.")
  public String fetchTodayRate() {
    context.authenticatedUser().validateHasCreatePermission("EXCHANGE_RATE");

    return exchangeRateService
        .fetchAndStoreTodayRate()
        .map(rate -> {
          Map<String, Object> response = new HashMap<>();
          response.put("status", "success");
          response.put("message", "Exchange rate fetched successfully");
          response.put("rate", rateToJson(rate));
          return gson.toJson(response);
        })
        .orElse("{\"status\": \"error\", \"message\": \"Failed to fetch exchange rate from BCCR\"}");
  }

  private String rateToJson(BccrExchangeRate rate) {
    Map<String, Object> json = new HashMap<>();
    json.put("id", rate.getId());
    json.put("rateDate", rate.getRateDate().toString());
    json.put("buyRate", rate.getBuyRate());
    json.put("sellRate", rate.getSellRate());
    json.put("referenceRate", rate.getReferenceRate());
    json.put("sourceCurrency", rate.getSourceCurrency());
    json.put("targetCurrency", rate.getTargetCurrency());
    json.put("fetchedAt", rate.getFetchedAt().toString());
    json.put("latest", rate.isLatest());
    return gson.toJson(json);
  }
}