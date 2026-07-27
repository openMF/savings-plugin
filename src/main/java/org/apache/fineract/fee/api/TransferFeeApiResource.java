/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.fee.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
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
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.exchangerate.domain.BccrExchangeRate;
import org.apache.fineract.exchangerate.service.BccrExchangeRateService;
import org.apache.fineract.fee.domain.TransferFee;
import org.apache.fineract.fee.domain.TransferFeeRepository;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Component
@Path("/v2/transfer-fees")
@Tag(
    name = "Self Transfer Fees Management",
    description = "Endpoints to manage transfer fee configurations")
@RequiredArgsConstructor
public class TransferFeeApiResource {

  private final PlatformSecurityContext contextBackOffice;
  private final TransferFeeRepository feeRepository;
  private final DefaultToApiJsonSerializer<TransferFee> toApiJsonSerializer;
  private final BccrExchangeRateService bccrExchangeRateService;
  private final Gson gson = new Gson();

  
  @GET  
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Get All Transfer Fees",
      description =
          "Retrieves all configured transfer fees. Includes the latest BCCR exchange rate only "
              + "for fees explicitly flagged as requiring exchange rate data.")
  public String getBackOfficeAll() {
    contextBackOffice.authenticatedUser().validateHasReadPermission("TRANSFER_FEE");

    List<TransferFee> fees = feeRepository.findAll();

    // Fetch the latest BCCR sell rate once for all fees
    Optional<BccrExchangeRate> latestRateOpt = bccrExchangeRateService.getLatestRate();
    BigDecimal currentBccrRate = latestRateOpt.map(BccrExchangeRate::getSellRate).orElse(null);

    List<Map<String, Object>> responseList =
        fees.stream()
            .map(
                fee -> {
                  Map<String, Object> feeMap = new HashMap<>();
                  feeMap.put("id", fee.getId());
                  feeMap.put("transferType", fee.getTransferType());
                  feeMap.put("currencyCode", fee.getCurrencyCode());
                  feeMap.put("transferMode", fee.getTransferMode());
                  feeMap.put("feeType", fee.getFeeType());
                  feeMap.put("feeValue", fee.getFeeValue());
                  feeMap.put("feeCurrency", fee.getFeeCurrency());
                  feeMap.put("thresholdAmount", fee.getThresholdAmount());
                  feeMap.put("thresholdFeeValue", fee.getThresholdFeeValue());
                  feeMap.put("description", fee.getDescription());
                  feeMap.put("isActive", fee.isActive());
                  feeMap.put("exchangeRateRequired", fee.isExchangeRateRequired());

                  // Only include BCCR rate when explicitly required by configuration
                  if (fee.isExchangeRateRequired()) {
                    feeMap.put("currentBccrRate", currentBccrRate);
                  }

                  return feeMap;
                })
            .collect(Collectors.toList());

    return gson.toJson(responseList);
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Create Transfer Fee",
      description = "Creates a new transfer fee configuration. Requires TRANSFER_FEE permission.")
  public String create(final String apiRequestBodyAsJson) {
    contextBackOffice.authenticatedUser().validateHasCreatePermission("TRANSFER_FEE");
    TransferFee fee = gson.fromJson(apiRequestBodyAsJson, TransferFee.class);
    fee.setId(null);
    feeRepository.save(fee);
    return toApiJsonSerializer.serialize(fee);
  }

  @PUT
  @Path("/{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Update Transfer Fee",
      description =
          "Updates an existing transfer fee configuration. Requires TRANSFER_FEE permission.")
  public String update(@PathParam("id") Long id, final String apiRequestBodyAsJson) {
    contextBackOffice.authenticatedUser().validateHasUpdatePermission("TRANSFER_FEE");
    TransferFee fee =
        feeRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Fee configuration not found"));

    TransferFee updates =
        gson.fromJson(apiRequestBodyAsJson, TransferFee.class);

    // Update all fields
    fee.setTransferType(updates.getTransferType());
    fee.setCurrencyCode(updates.getCurrencyCode());
    fee.setTransferMode(updates.getTransferMode());
    fee.setFeeType(updates.getFeeType());
    fee.setFeeValue(updates.getFeeValue());
    fee.setFeeCurrency(updates.getFeeCurrency());
    fee.setThresholdAmount(updates.getThresholdAmount());
    fee.setThresholdFeeValue(updates.getThresholdFeeValue());
    fee.setDescription(updates.getDescription());
    fee.setActive(updates.isActive());
    fee.setExchangeRateRequired(updates.isExchangeRateRequired());

    feeRepository.save(fee);
    return toApiJsonSerializer.serialize(fee);
  }

  @DELETE
  @Path("/{id}")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Delete Transfer Fee",
      description = "Deletes a transfer fee configuration. Requires TRANSFER_FEE permission.")
  public String delete(@PathParam("id") Long id) {
    contextBackOffice.authenticatedUser().validateHasDeletePermission("TRANSFER_FEE");
    if (!feeRepository.existsById(id)) {
      throw new IllegalArgumentException("Fee configuration not found");
    }
    feeRepository.deleteById(id);
    return "{\"status\": \"deleted\", \"resourceId\": " + id + "}";
  }
}
