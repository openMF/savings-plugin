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
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.exchangerate.data.BccrExchangeRateRequest;
import org.apache.fineract.exchangerate.domain.BccrExchangeRate;
import org.apache.fineract.exchangerate.service.BccrExchangeRateService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v2/exchange-rates")
@Component
@Tag(
    name = "BCCR Exchange Rates",
    description = "API for accessing and managing exchange rates from the Central Bank of Costa Rica (BCCR)")
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
  @Operation(summary = "Get Current Exchange Rate", description = "Returns the latest available USD/CRC exchange rate from BCCR.")
  public String getCurrentRate() {
    context.authenticatedUser().validateHasReadPermission("EXCHANGE_RATE");
    return exchangeRateService.getCurrentDailyRate().map(gson::toJson).orElse("{\"error\": \"No exchange rate available\"}");
  }

  @GET
  @Path("/latest")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Get Latest Exchange Rate", description = "Returns the most recent exchange rate with full details.")
  public String getLatestRate() {
    context.authenticatedUser().validateHasReadPermission("EXCHANGE_RATE");
    return exchangeRateService.getLatestRate().map(this::rateToJson).orElse("{\"error\": \"No exchange rate available\"}");
  }

  @GET
  @Path("/{date}")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Get Exchange Rate by Date", description = "Returns the exchange rate for a specific date (format: yyyy-MM-dd).")
  public String getRateByDate(@PathParam("date") @Parameter(description = "Date in yyyy-MM-dd format") String date) {
    context.authenticatedUser().validateHasReadPermission("EXCHANGE_RATE");
    LocalDate localDate = LocalDate.parse(date, DATE_FORMAT);
    return exchangeRateService.getRateForDate(localDate).map(this::rateToJson).orElse("{\"error\": \"No exchange rate available for date: " + date + "\"}");
  }

  @GET
  @Path("/historical")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Get Historical Exchange Rates", description = "Returns exchange rates for a date range.")
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
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Create Exchange Rate", description = "Manually creates a new BCCR exchange rate record.")
  public String createRate(final String apiRequestBodyAsJson) {
    context.authenticatedUser().validateHasCreatePermission("EXCHANGE_RATE");

    // 1. Deserializar a DTO (evita problemas de Gson con LocalDate)
    BccrExchangeRateRequest req = gson.fromJson(apiRequestBodyAsJson, BccrExchangeRateRequest.class);
    
    // 2. Convertir String a LocalDate manualmente
    LocalDate date = LocalDate.parse(req.getRateDate(), DATE_FORMAT);
    
    // 3. Mapear a la entidad de dominio
    BccrExchangeRate rate = new BccrExchangeRate();
    rate.setRateDate(date);
    rate.setBuyIndicatorCode(req.getBuyIndicatorCode());
    rate.setSellIndicatorCode(req.getSellIndicatorCode());
    rate.setBuyRate(req.getBuyRate());
    rate.setSellRate(req.getSellRate());
    rate.setReferenceRate(req.getReferenceRate());
    rate.setSourceCurrency(req.getSourceCurrency() != null ? req.getSourceCurrency() : "USD");
    rate.setTargetCurrency(req.getTargetCurrency() != null ? req.getTargetCurrency() : "CRC");
    rate.setFetchedAt(LocalDateTime.now());
    rate.setLatest(req.getLatest() != null ? req.getLatest() : false);

    // 4. Guardar
    BccrExchangeRate savedRate = exchangeRateService.createRate(rate);
    
    Map<String, Object> response = new HashMap<>();
    response.put("status", "success");
    response.put("message", "Exchange rate created successfully");
    response.put("rate", rateToJson(savedRate));
    
    return gson.toJson(response);
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

  @PUT
  @Path("/{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Update Exchange Rate", description = "Updates an existing BCCR exchange rate record.")
  public String updateRate(@PathParam("id") Long id, final String apiRequestBodyAsJson) {
    context.authenticatedUser().validateHasUpdatePermission("EXCHANGE_RATE");

    BccrExchangeRateRequest req = gson.fromJson(apiRequestBodyAsJson, BccrExchangeRateRequest.class);
    BccrExchangeRate rate = exchangeRateService.getRateById(id)
        .orElseThrow(() -> new IllegalArgumentException("Exchange rate not found with id: " + id));
    
    // 2. Convertir String a LocalDate manualmente
    LocalDate date = LocalDate.parse(req.getRateDate(), DATE_FORMAT);

    if (req.getRateDate() != null) rate.setRateDate(date);
    if (req.getBuyIndicatorCode() != null) rate.setBuyIndicatorCode(req.getBuyIndicatorCode());
    if (req.getSellIndicatorCode() != null) rate.setSellIndicatorCode(req.getSellIndicatorCode());
    if (req.getBuyRate() != null) rate.setBuyRate(req.getBuyRate());
    if (req.getSellRate() != null) rate.setSellRate(req.getSellRate());
    if (req.getReferenceRate() != null) rate.setReferenceRate(req.getReferenceRate());
    if (req.getSourceCurrency() != null) rate.setSourceCurrency(req.getSourceCurrency());
    if (req.getTargetCurrency() != null) rate.setTargetCurrency(req.getTargetCurrency());
    if (req.getLatest() != null) rate.setLatest(req.getLatest());

    BccrExchangeRate updatedRate = exchangeRateService.updateRate(rate);
    return gson.toJson(Map.of("status", "success", "rate", rateToJson(updatedRate)));
  }

  @DELETE
  @Path("/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Delete Exchange Rate", description = "Deletes a BCCR exchange rate record.")
  public String deleteRate(@PathParam("id") Long id) {
    context.authenticatedUser().validateHasDeletePermission("EXCHANGE_RATE");
    exchangeRateService.deleteRate(id);
    return gson.toJson(Map.of("status", "success", "message", "Exchange rate deleted successfully"));
  }

  @POST
  @Path("/fetch")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Manually Fetch Exchange Rate", description = "Triggers a manual fetch of today's exchange rate from BCCR. Requires ADMIN permission.")
  public String fetchTodayRate() {
    context.authenticatedUser().validateHasCreatePermission("EXCHANGE_RATE");

    return exchangeRateService.fetchAndStoreTodayRate()
        .map(rate -> {
          Map<String, Object> response = new HashMap<>();
          response.put("status", "success");
          response.put("message", "Exchange rate fetched successfully");
          response.put("rate", rateToJson(rate));
          return gson.toJson(response);
        })
        .orElse("{\"status\": \"error\", \"message\": \"Failed to fetch exchange rate from BCCR\"}");
  }

 
}