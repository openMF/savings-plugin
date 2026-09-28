/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.baseteller.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.baseteller.data.CatalogUpdateCategory;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

class CatalogCacheRefresherTest {

  private final CacheManager cacheManager = mock(CacheManager.class);
  private final Cache cache = mock(Cache.class);
  private final CatalogCacheRefresher refresher = new CatalogCacheRefresher(cacheManager);

  @BeforeEach
  void setTenant() {
    ThreadLocalContextUtil.setTenant(
        new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
    when(cacheManager.getCache(org.mockito.ArgumentMatchers.anyString())).thenReturn(cache);
  }

  @AfterEach
  void clearTenant() {
    ThreadLocalContextUtil.clearTenant();
  }

  @Test
  void generalEvictsOnlyKnownTenantAndOfficeKeys() {
    refresher.refresh(CatalogUpdateCategory.GENERAL, 1L, ".");

    verify(cache, times(2)).evict("defaultpayment_types");
    verify(cache, times(2)).evict("default.of");
    verify(cache).evict("default.ofd");
    verify(cache).evict("default1");
  }

  @Test
  void usersEvictsOfficeScopedUserList() {
    refresher.refresh(CatalogUpdateCategory.USERS, 1L, ".");

    verify(cache).evict("default.");
  }

  @Test
  void accountingDoesNotGloballyClearCaches() {
    refresher.refresh(CatalogUpdateCategory.ACCOUNTING, 1L, ".");

    verify(cacheManager, never()).getCache(org.mockito.ArgumentMatchers.anyString());
    verify(cache, never()).clear();
  }
}
