/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object representing the BCCR service configuration loaded from the {@code
 * c_external_service_properties} table.
 *
 * <p>Each tenant can have its own BCCR configuration, allowing multi-tenant deployments to use
 * different BCCR subscriptions or settings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BccrServiceConfiguration {

  /** Base URL of the BCCR Web Service. */
  private String host;

  /** Subscription token obtained from BCCR. */
  private String token;

  /** Name of the subscriber registered with BCCR. */
  private String subscriberName;

  /** Email of the subscriber registered with BCCR. */
  private String subscriberEmail;

  /** BCCR indicator code for the buy rate (typically "317"). */
  private String buyIndicatorCode;

  /** BCCR indicator code for the sell rate (typically "318"). */
  private String sellIndicatorCode;

  /** Whether the scheduler is enabled for this tenant. */
  private boolean schedulerEnabled;

  /** Cron expression for the scheduler. */
  private String schedulerCron;

  /** Number of days to backfill when fetching historical rates. */
  private int backfillDays;

  /** Timezone for the scheduler (e.g., "America/Costa_Rica"). */
  private String timezone;

  /** Whether the BCCR service is enabled for this tenant. */
  private boolean enabled;

  /**
   * Returns a default configuration when no configuration is found in the database.
   *
   * @return a default BccrServiceConfiguration
   */
  public static BccrServiceConfiguration defaultConfiguration() {
    return BccrServiceConfiguration.builder()
        .host("https://gee.bccr.fi.cr/Indicadores/Suscripciones/WS/wsindicadoreseconomicos.asmx")
        .token("")
        .subscriberName("Fineract Self Service")
        .subscriberEmail("admin@fineract.org")
        .buyIndicatorCode("317")
        .sellIndicatorCode("318")
        .schedulerEnabled(false)
        .schedulerCron("0 0 8 * * *")
        .backfillDays(7)
        .timezone("America/Costa_Rica")
        .enabled(false)
        .build();
  }

  /**
   * Checks if the configuration is valid and ready to use.
   *
   * @return true if the configuration is valid
   */
  public boolean isValid() {
    return enabled
        && host != null
        && !host.isBlank()
        && token != null
        && !token.isBlank()
        && !token.equals("YOUR_BCCR_TOKEN_HERE");
  }
}
