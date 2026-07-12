/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.exchangerate.data.BccrDailyRate;
import org.apache.fineract.exchangerate.data.BccrIndicatorResponse;
import org.apache.fineract.exchangerate.domain.BccrExchangeRate;
import org.apache.fineract.exchangerate.domain.BccrExchangeRateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for managing BCCR exchange rates.
 *
 * <p>This service coordinates the fetching of exchange rates from the BCCR Web Service,
 * stores them in the database, and provides conversion utilities for the transfer fee system.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BccrExchangeRateServiceImpl implements BccrExchangeRateService {

  private static final ZoneId COSTA_RICA_ZONE = ZoneId.of("America/Costa_Rica");

  private final BccrWebServiceClient bccrClient;
  private final BccrExchangeRateRepository rateRepository;

  @Override
  @Transactional
  public Optional<BccrExchangeRate> fetchAndStoreTodayRate() {
    LocalDate today = LocalDate.now(COSTA_RICA_ZONE);
    log.info("Fetching BCCR exchange rates for date: {}", today);

    // Check if we already have today's rate
    if (rateRepository.existsByRateDate(today)) {
      log.info("Exchange rate for {} already exists. Skipping fetch.", today);
      return rateRepository.findByRateDate(today);
    }

    List<BccrIndicatorResponse> indicators = bccrClient.fetchDailyRates(today);

    if (indicators.isEmpty()) {
      log.warn("No exchange rate data returned from BCCR for date: {}", today);
      return Optional.empty();
    }

    BigDecimal buyRate = null;
    BigDecimal sellRate = null;

    for (BccrIndicatorResponse indicator : indicators) {
      if ("317".equals(indicator.getIndicatorCode())) {
        buyRate = indicator.getValue();
      } else if ("318".equals(indicator.getIndicatorCode())) {
        sellRate = indicator.getValue();
      }
    }

    if (buyRate == null || sellRate == null) {
      log.warn("Incomplete rate data from BCCR for date: {}. Buy: {}, Sell: {}",
          today, buyRate, sellRate);
      // If we have at least one rate, use it for both (fallback)
      if (buyRate != null) {
        sellRate = buyRate;
      } else if (sellRate != null) {
        buyRate = sellRate;
      } else {
        return Optional.empty();
      }
    }

    // Reset all existing "latest" flags
    rateRepository.resetLatestFlags();

    // Create and save the new rate
    BccrExchangeRate newRate = BccrExchangeRate.instance(
        today,
        buyRate,
        sellRate,
        LocalDateTime.now(COSTA_RICA_ZONE));

    BccrExchangeRate savedRate = rateRepository.save(newRate);
    log.info("Successfully stored BCCR exchange rate for {}: Buy={}, Sell={}, Reference={}",
        today, buyRate, sellRate, savedRate.getReferenceRate());

    return Optional.of(savedRate);
  }

  @Override
  @Transactional
  public int fetchAndStoreRatesForRange(LocalDate fromDate, LocalDate toDate) {
    log.info("Fetching BCCR exchange rates for range: {} to {}", fromDate, toDate);

    List<BccrIndicatorResponse> indicators = bccrClient.fetchRatesForRange(fromDate, toDate);

    if (indicators.isEmpty()) {
      log.warn("No exchange rate data returned from BCCR for range: {} to {}", fromDate, toDate);
      return 0;
    }

    // Group indicators by date
    Map<LocalDate, BigDecimal[]> ratesByDate = new HashMap<>();
    for (BccrIndicatorResponse indicator : indicators) {
      ratesByDate.computeIfAbsent(indicator.getDate(), k -> new BigDecimal[2]);
      if ("317".equals(indicator.getIndicatorCode())) {
        ratesByDate.get(indicator.getDate())[0] = indicator.getValue();
      } else if ("318".equals(indicator.getIndicatorCode())) {
        ratesByDate.get(indicator.getDate())[1] = indicator.getValue();
      }
    }

    int storedCount = 0;
    for (Map.Entry<LocalDate, BigDecimal[]> entry : ratesByDate.entrySet()) {
      LocalDate date = entry.getKey();
      BigDecimal[] rates = entry.getValue();

      // Skip if already exists
      if (rateRepository.existsByRateDate(date)) {
        log.debug("Rate for {} already exists. Skipping.", date);
        continue;
      }

      BigDecimal buyRate = rates[0];
      BigDecimal sellRate = rates[1];

      if (buyRate == null || sellRate == null) {
        log.warn("Incomplete rate data for date: {}. Buy: {}, Sell: {}", date, buyRate, sellRate);
        if (buyRate != null) {
          sellRate = buyRate;
        } else if (sellRate != null) {
          buyRate = sellRate;
        } else {
          continue;
        }
      }

      BccrExchangeRate rate = BccrExchangeRate.instance(
          date,
          buyRate,
          sellRate,
          LocalDateTime.now(COSTA_RICA_ZONE));
      rate.setLatest(false); // Historical rates are not "latest"

      rateRepository.save(rate);
      storedCount++;
      log.debug("Stored historical rate for {}: Buy={}, Sell={}", date, buyRate, sellRate);
    }

    log.info("Successfully stored {} historical exchange rates", storedCount);
    return storedCount;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BccrExchangeRate> getLatestRate() {
    return rateRepository.findLatestRate();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BccrExchangeRate> getRateForDate(LocalDate date) {
    return rateRepository.findByRateDate(date);
  }

  @Override
  @Transactional(readOnly = true)
  public List<BccrExchangeRate> getHistoricalRates(LocalDate fromDate, LocalDate toDate) {
    return rateRepository.findByRateDateBetweenOrderByRateDateDesc(fromDate, toDate);
  }

  @Override
  @Transactional(readOnly = true)
  public BigDecimal convertUsdToCrc(BigDecimal amountInUsd) {
    if (amountInUsd == null || amountInUsd.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }

    Optional<BccrExchangeRate> latestRate = getLatestRate();
    if (latestRate.isEmpty()) {
      log.warn("No exchange rate available for USD to CRC conversion. Returning original amount.");
      return amountInUsd;
    }

    return latestRate.get().convertUsdToCrc(amountInUsd);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BccrDailyRate> getCurrentDailyRate() {
    return getLatestRate().map(rate -> BccrDailyRate.builder()
        .date(rate.getRateDate())
        .buyRate(rate.getBuyRate())
        .sellRate(rate.getSellRate())
        .referenceRate(rate.getReferenceRate())
        .sourceCurrency(rate.getSourceCurrency())
        .targetCurrency(rate.getTargetCurrency())
        .build());
  }
}