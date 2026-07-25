/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.exchangerate.data.CurrencyConversionData;
import org.apache.fineract.exchangerate.data.ExchangeRateData;
import org.apache.fineract.exchangerate.service.ExchangeRateReadPlatformService;
import org.apache.fineract.exchangerate.service.ExchangeRateWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExchangeRateApiResourceTest {

  @Mock private PlatformSecurityContext context;
  @Mock private ExchangeRateReadPlatformService readService;
  @Mock private ExchangeRateWritePlatformService writeService;
  @Mock private DefaultToApiJsonSerializer<ExchangeRateData> exchangeRateSerializer;
  @Mock private DefaultToApiJsonSerializer<CurrencyConversionData> conversionSerializer;

  private ExchangeRateApiResource resource;

  @BeforeEach
  void setUp() {
    this.resource =
        new ExchangeRateApiResource(
            this.context,
            this.readService,
            this.writeService,
            this.exchangeRateSerializer,
            this.conversionSerializer);
  }

  @Test
  void retrieveExchangeRatesRequiresReadPermissionAndDelegates() {
    final AppUser user = mockAuthenticatedUser();
    final List<ExchangeRateData> data =
        List.of(
            ExchangeRateData.instance(
                1L,
                "USD",
                "JPY",
                new BigDecimal("155.125000000000"),
                LocalDate.parse("2026-01-01"),
                null,
                true,
                null,
                null));
    when(this.readService.retrieveAll("USD", "JPY", true, LocalDate.parse("2026-07-25")))
        .thenReturn(data);
    when(this.exchangeRateSerializer.serializeResult(data)).thenReturn("[]");

    final String result = this.resource.retrieveExchangeRates("USD", "JPY", true, "2026-07-25");

    assertNotNull(result);
    verify(user).validateHasReadPermission("EXCHANGE_RATE");
    verify(this.readService).retrieveAll("USD", "JPY", true, LocalDate.parse("2026-07-25"));
  }

  @Test
  void createExchangeRateRequiresCreatePermissionAndDelegates() {
    final AppUser user = mockAuthenticatedUser();
    final String jsonBody = "{\"sourceCurrency\":\"USD\",\"targetCurrency\":\"JPY\"}";
    final CommandProcessingResult result =
        new CommandProcessingResultBuilder().withEntityId(1L).build();
    when(this.writeService.createExchangeRate(jsonBody)).thenReturn(result);
    when(this.exchangeRateSerializer.serializeResult(result)).thenReturn("{}");

    assertNotNull(this.resource.createExchangeRate(jsonBody));

    verify(user).validateHasCreatePermission("EXCHANGE_RATE");
    verify(this.writeService).createExchangeRate(jsonBody);
  }

  @Test
  void updateExchangeRateRequiresUpdatePermissionAndDelegates() {
    final AppUser user = mockAuthenticatedUser();
    final String jsonBody = "{\"exchangeRate\":156}";
    final CommandProcessingResult result =
        new CommandProcessingResultBuilder().withEntityId(1L).build();
    when(this.writeService.updateExchangeRate(1L, jsonBody)).thenReturn(result);
    when(this.exchangeRateSerializer.serializeResult(result)).thenReturn("{}");

    assertNotNull(this.resource.updateExchangeRate(1L, jsonBody));

    verify(user).validateHasUpdatePermission("EXCHANGE_RATE");
    verify(this.writeService).updateExchangeRate(1L, jsonBody);
  }

  @Test
  void deleteExchangeRateRequiresDeletePermissionAndDelegates() {
    final AppUser user = mockAuthenticatedUser();
    final CommandProcessingResult result =
        new CommandProcessingResultBuilder().withEntityId(1L).build();
    when(this.writeService.deleteExchangeRate(1L)).thenReturn(result);
    when(this.exchangeRateSerializer.serializeResult(result)).thenReturn("{}");

    assertNotNull(this.resource.deleteExchangeRate(1L));

    verify(user).validateHasDeletePermission("EXCHANGE_RATE");
    verify(this.writeService).deleteExchangeRate(1L);
  }

  @Test
  void convertRequiresConvertCurrencyPermissionAndDelegates() {
    final AppUser user = mockAuthenticatedUser();
    final String jsonBody =
        "{\"sourceCurrency\":\"USD\",\"targetCurrency\":\"JPY\",\"amount\":1,\"conversionDate\":\"2026-07-25\"}";
    final CurrencyConversionData data =
        CurrencyConversionData.instance(
            "USD",
            "JPY",
            new BigDecimal("155.125000000000"),
            BigDecimal.ONE,
            new BigDecimal("155"),
            LocalDate.parse("2026-07-25"));
    when(this.readService.convert(jsonBody)).thenReturn(data);
    when(this.conversionSerializer.serializeResult(data)).thenReturn("{}");

    assertNotNull(this.resource.convert(jsonBody));

    verify(user).validateHasPermissionTo("CONVERT_CURRENCY");
    verify(this.readService).convert(jsonBody);
  }

  private AppUser mockAuthenticatedUser() {
    final AppUser user = mock(AppUser.class);
    when(this.context.authenticatedUser()).thenReturn(user);
    return user;
  }
}
