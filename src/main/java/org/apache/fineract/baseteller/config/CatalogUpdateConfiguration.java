/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.config;

import java.time.Clock;
import java.time.Duration;
import org.apache.fineract.baseteller.data.CatalogUpdateCategory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration(proxyBeanMethods = false)
public class CatalogUpdateConfiguration {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock catalogUpdateClock() {
    return Clock.systemUTC();
  }

  @Component
  @ConfigurationProperties(prefix = "fineract.baseteller.catalog-updates")
  public static class CatalogUpdateProperties {

    private Duration generalFreshness = Duration.ofHours(24);
    private Duration accountingFreshness = Duration.ofHours(24);
    private Duration usersFreshness = Duration.ofHours(24);
    private Duration updatingTimeout = Duration.ofMinutes(5);

    public Duration freshness(final CatalogUpdateCategory category) {
      return switch (category) {
        case GENERAL -> generalFreshness;
        case ACCOUNTING -> accountingFreshness;
        case USERS -> usersFreshness;
      };
    }

    public Duration getGeneralFreshness() {
      return generalFreshness;
    }

    public void setGeneralFreshness(final Duration generalFreshness) {
      this.generalFreshness = positive(generalFreshness, "generalFreshness");
    }

    public Duration getAccountingFreshness() {
      return accountingFreshness;
    }

    public void setAccountingFreshness(final Duration accountingFreshness) {
      this.accountingFreshness = positive(accountingFreshness, "accountingFreshness");
    }

    public Duration getUsersFreshness() {
      return usersFreshness;
    }

    public void setUsersFreshness(final Duration usersFreshness) {
      this.usersFreshness = positive(usersFreshness, "usersFreshness");
    }

    public Duration getUpdatingTimeout() {
      return updatingTimeout;
    }

    public void setUpdatingTimeout(final Duration updatingTimeout) {
      this.updatingTimeout = positive(updatingTimeout, "updatingTimeout");
    }

    private static Duration positive(final Duration value, final String name) {
      if (value == null || value.isZero() || value.isNegative()) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      return value;
    }
  }
}
