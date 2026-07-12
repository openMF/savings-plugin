/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.exchangerate.service.BccrConfigurationService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

/**
 * REST API resource for managing BCCR service configuration.
 *
 * <p>Provides endpoints for:
 *
 * <ul>
 *   <li>Getting the current BCCR configuration
 *   <li>Invalidating the configuration cache
 *   <li>Checking if the service is enabled
 * </ul>
 */
@Path("/v2/exchange-rates/configuration")
@Component
@Tag(
    name = "BCCR Exchange Rate Configuration",
    description = "API for managing BCCR service configuration per tenant")
@RequiredArgsConstructor
@Slf4j
public class BccrConfigurationApiResource {

  private final BccrConfigurationService configurationService;
  private final PlatformSecurityContext context;
  private final Gson gson = new Gson();

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Get BCCR Configuration",
      description =
          "Returns the current BCCR service configuration for the tenant. Sensitive data like the token is masked.")
  public String getConfiguration() {
    context.authenticatedUser().validateHasReadPermission("EXCHANGE_RATE");

    var config = configurationService.getConfiguration();
    // Mask the token for security
    var maskedConfig = maskSensitiveData(config);
    return gson.toJson(maskedConfig);
  }

  @POST
  @Path("/cache/invalidate")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Invalidate Configuration Cache",
      description =
          "Invalidates the cached BCCR configuration, forcing a reload from the database on the next access.")
  public String invalidateCache() {
    context.authenticatedUser().validateHasUpdatePermission("EXCHANGE_RATE");

    configurationService.invalidateCache();
    return "{\"status\": \"success\", \"message\": \"Configuration cache invalidated\"}";
  }

  @GET
  @Path("/status")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Get BCCR Service Status",
      description = "Returns whether the BCCR service is enabled and properly configured.")
  public String getStatus() {
    context.authenticatedUser().validateHasReadPermission("EXCHANGE_RATE");

    boolean enabled = configurationService.isEnabled();
    return String.format("{\"enabled\": %b}", enabled);
  }

  private Object maskSensitiveData(Object config) {
    // Create a masked version for API response
    var original = (org.apache.fineract.exchangerate.data.BccrServiceConfiguration) config;
    var masked = new java.util.HashMap<String, Object>();
    masked.put("host", original.getHost());
    masked.put("token", maskToken(original.getToken()));
    masked.put("subscriberName", original.getSubscriberName());
    masked.put("subscriberEmail", original.getSubscriberEmail());
    masked.put("buyIndicatorCode", original.getBuyIndicatorCode());
    masked.put("sellIndicatorCode", original.getSellIndicatorCode());
    masked.put("schedulerEnabled", original.isSchedulerEnabled());
    masked.put("schedulerCron", original.getSchedulerCron());
    masked.put("backfillDays", original.getBackfillDays());
    masked.put("timezone", original.getTimezone());
    masked.put("enabled", original.isEnabled());
    masked.put("valid", original.isValid());
    return masked;
  }

  private String maskToken(String token) {
    if (token == null || token.length() <= 8) {
      return "****";
    }
    return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
  }
}
