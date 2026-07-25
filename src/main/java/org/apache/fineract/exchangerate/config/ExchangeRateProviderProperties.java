/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Configuration for optional external exchange-rate provider synchronization. */
@Component
public class ExchangeRateProviderProperties {

  private boolean providerEnabled;
  private String provider = "frankfurter";
  private String providerBaseUrl = "https://api.frankfurter.dev/v1";
  private String baseCurrency = "USD";
  private boolean syncEnabled;

  public boolean isProviderEnabled() {
    return this.providerEnabled;
  }

  @Value("${exchange-rate.provider.enabled:false}")
  public void setProviderEnabled(final boolean providerEnabled) {
    this.providerEnabled = providerEnabled;
  }

  public String getProvider() {
    return this.provider;
  }

  @Value("${exchange-rate.provider:frankfurter}")
  public void setProvider(final String provider) {
    this.provider = provider;
  }

  public String getProviderBaseUrl() {
    return this.providerBaseUrl;
  }

  @Value("${exchange-rate.provider.base-url:https://api.frankfurter.dev/v1}")
  public void setProviderBaseUrl(final String providerBaseUrl) {
    this.providerBaseUrl = providerBaseUrl;
  }

  public String getBaseCurrency() {
    return this.baseCurrency;
  }

  @Value("${exchange-rate.base-currency:USD}")
  public void setBaseCurrency(final String baseCurrency) {
    this.baseCurrency = baseCurrency;
  }

  public boolean isSyncEnabled() {
    return this.syncEnabled;
  }

  @Value("${exchange-rate.sync.enabled:false}")
  public void setSyncEnabled(final boolean syncEnabled) {
    this.syncEnabled = syncEnabled;
  }
}
