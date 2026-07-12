/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.exchangerate.data.BccrServiceConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Service responsible for loading BCCR service configuration from the Apache Fineract
 * {@code c_external_service_properties} table.
 *
 * <p>This service provides tenant-specific configuration, allowing each tenant to have its own BCCR
 * subscription and settings. Configuration is cached for 5 minutes to avoid excessive database
 * queries.
 *
 * <p>The configuration is loaded from the following properties:
 * <ul>
 *   <li>{@code host} - Base URL of the BCCR Web Service</li>
 *   <li>{@code token} - Subscription token</li>
 *   <li>{@code subscriberName} - Subscriber name</li>
 *   <li>{@code subscriberEmail} - Subscriber email</li>
 *   <li>{@code buyIndicatorCode} - BCCR indicator code for buy rate</li>
 *   <li>{@code sellIndicatorCode} - BCCR indicator code for sell rate</li>
 *   <li>{@code schedulerEnabled} - Whether scheduler is enabled</li>
 *   <li>{@code schedulerCron} - Cron expression for scheduler</li>
 *   <li>{@code backfillDays} - Number of days to backfill</li>
 *   <li>{@code timezone} - Timezone for scheduler</li>
 *   <li>{@code isEnabled} - Whether the service is enabled</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BccrConfigurationService {

  private static final String SERVICE_NAME = "BccrService";
  private static final long CACHE_TTL_MILLIS = TimeUnit.MINUTES.toMillis(5);

  private final JdbcTemplate jdbcTemplate;

  private volatile BccrServiceConfiguration cachedConfiguration;
  private volatile long cacheTimestamp;

  /**
   * Retrieves the BCCR service configuration for the current tenant.
   *
   * <p>The configuration is cached for 5 minutes to improve performance. If the cache is expired or
   * empty, the configuration is reloaded from the database.
   *
   * @return the BCCR service configuration, or a default configuration if not found
   */
  public BccrServiceConfiguration getConfiguration() {
    long now = System.currentTimeMillis();
    if (cachedConfiguration != null && (now - cacheTimestamp) < CACHE_TTL_MILLIS) {
      return cachedConfiguration;
    }

    synchronized (this) {
      // Double-check after acquiring lock
      if (cachedConfiguration != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MILLIS) {
        return cachedConfiguration;
      }

      cachedConfiguration = loadConfigurationFromDatabase();
      cacheTimestamp = System.currentTimeMillis();
      return cachedConfiguration;
    }
  }

  /**
   * Invalidates the cached configuration, forcing a reload on the next access.
   *
   * <p>This method should be called when the configuration is updated in the database.
   */
  public void invalidateCache() {
    synchronized (this) {
      cachedConfiguration = null;
      cacheTimestamp = 0;
      log.info("BCCR configuration cache invalidated");
    }
  }

  /**
   * Checks if the BCCR service is enabled for the current tenant.
   *
   * @return true if the service is enabled and properly configured
   */
  public boolean isEnabled() {
    return getConfiguration().isValid();
  }

  private BccrServiceConfiguration loadConfigurationFromDatabase() {
    log.debug("Loading BCCR configuration from database for service: {}", SERVICE_NAME);

    try {
      String sql =
          "SELECT p.name, p.value FROM c_external_service_properties p "
              + "INNER JOIN c_external_service s ON p.external_service_id = s.id "
              + "WHERE s.name = ?";

      List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, SERVICE_NAME);

      if (rows.isEmpty()) {
        log.warn(
            "No BCCR configuration found in c_external_service_properties for service '{}'. Using default configuration.",
            SERVICE_NAME);
        return BccrServiceConfiguration.defaultConfiguration();
      }

      Map<String, String> properties = new HashMap<>();
      for (Map<String, Object> row : rows) {
        String name = (String) row.get("name");
        String value = (String) row.get("value");
        if (name != null && value != null) {
          properties.put(name, value);
        }
      }

      BccrServiceConfiguration config = mapToConfiguration(properties);
      log.info(
          "Loaded BCCR configuration: host={}, enabled={}, schedulerEnabled={}, backfillDays={}",
          config.getHost(),
          config.isEnabled(),
          config.isSchedulerEnabled(),
          config.getBackfillDays());

      return config;

    } catch (Exception e) {
      log.error("Failed to load BCCR configuration from database: {}", e.getMessage(), e);
      return BccrServiceConfiguration.defaultConfiguration();
    }
  }

  private BccrServiceConfiguration mapToConfiguration(Map<String, String> properties) {
    return BccrServiceConfiguration.builder()
        .host(properties.getOrDefault("host", ""))
        .token(properties.getOrDefault("token", ""))
        .subscriberName(properties.getOrDefault("subscriberName", "Fineract Self Service"))
        .subscriberEmail(properties.getOrDefault("subscriberEmail", "admin@fineract.org"))
        .buyIndicatorCode(properties.getOrDefault("buyIndicatorCode", "317"))
        .sellIndicatorCode(properties.getOrDefault("sellIndicatorCode", "318"))
        .schedulerEnabled(parseBoolean(properties.get("schedulerEnabled"), true))
        .schedulerCron(properties.getOrDefault("schedulerCron", "0 0 8 * * *"))
        .backfillDays(parseInt(properties.get("backfillDays"), 7))
        .timezone(properties.getOrDefault("timezone", "America/Costa_Rica"))
        .enabled(parseBoolean(properties.get("isEnabled"), true))
        .build();
  }

  private boolean parseBoolean(String value, boolean defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return "true".equalsIgnoreCase(value.trim());
  }

  private int parseInt(String value, int defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      log.warn("Invalid integer value '{}', using default: {}", value, defaultValue);
      return defaultValue;
    }
  }
}