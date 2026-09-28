/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.service;

import org.apache.fineract.baseteller.data.CatalogUpdateCategory;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/** Evicts only known current-tenant/current-office Fineract cache keys. */
@Component
public class CatalogCacheRefresher {

  private final CacheManager cacheManager;

  public CatalogCacheRefresher(
      @Qualifier("defaultCacheManager") final CacheManager cacheManager) {
    this.cacheManager = cacheManager;
  }

  public void refresh(
      final CatalogUpdateCategory category, final Long officeId, final String officeHierarchy) {
    final String tenant = ThreadLocalContextUtil.getTenant().getTenantIdentifier();
    switch (category) {
      case GENERAL -> {
        evict("payment_types", tenant + "payment_types");
        evict("paymentTypesWithCode", tenant + "payment_types");
        evict("offices", tenant + officeHierarchy + "of");
        evict("officesForDropdown", tenant + officeHierarchy + "ofd");
        evict("officesById", tenant + officeId);
        evict("tellers", tenant + officeHierarchy + "of");
      }
      case USERS -> evict("users", tenant + officeHierarchy);
      case ACCOUNTING -> {
        // The validated accounting services in the pinned Fineract version are not cache-backed.
      }
    }
  }

  private void evict(final String cacheName, final Object key) {
    final Cache cache = cacheManager.getCache(cacheName);
    if (cache != null) {
      cache.evict(key);
    }
  }
}
