/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.exchangerate.service;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.exchangerate.data.BccrIndicatorResponse;
import org.apache.fineract.exchangerate.data.BccrServiceConfiguration;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Client for consuming the BCCR (Banco Central de Costa Rica) Web Service of Economic Indicators.
 *
 * <p>This client connects to the official SOAP endpoint to retrieve exchange rates published by the
 * Central Bank. Configuration is loaded from the {@code c_external_service_properties} table,
 * allowing each tenant to have its own BCCR subscription.
 *
 * @see <a href="https://www.bccr.fi.cr/indicadores-economicos/servicio-web">BCCR Web Service Registration</a>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BccrWebServiceClient {

  private static final String OBTENER_INDICADORES_METHOD = "/ObtenerIndicadoresEconomicosXML";
  private static final DateTimeFormatter BCCR_DATE_FORMAT =
      DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private final RestTemplate restTemplate;
  private final BccrConfigurationService configurationService;

  /**
   * Fetches the buy rate for a specific date using the configured indicator code.
   *
   * @param date the date to query
   * @return the buy rate response, or null if not available
   */
  public BccrIndicatorResponse fetchBuyRate(LocalDate date) {
    BccrServiceConfiguration config = configurationService.getConfiguration();
    return fetchIndicator(config.getBuyIndicatorCode(), date, "Tipo de Cambio Compra USD", config);
  }

  /**
   * Fetches the sell rate for a specific date using the configured indicator code.
   *
   * @param date the date to query
   * @return the sell rate response, or null if not available
   */
  public BccrIndicatorResponse fetchSellRate(LocalDate date) {
    BccrServiceConfiguration config = configurationService.getConfiguration();
    return fetchIndicator(config.getSellIndicatorCode(), date, "Tipo de Cambio Venta USD", config);
  }

  /**
   * Fetches both buy and sell rates for a specific date.
   *
   * @param date the date to query
   * @return list of indicator responses (buy and sell)
   */
  public List<BccrIndicatorResponse> fetchDailyRates(LocalDate date) {
    BccrServiceConfiguration config = configurationService.getConfiguration();

    if (!config.isValid()) {
      log.warn("BCCR service is not properly configured. Cannot fetch exchange rates.");
      return List.of();
    }

    List<BccrIndicatorResponse> rates = new ArrayList<>();

    BccrIndicatorResponse buyRate =
        fetchIndicator(config.getBuyIndicatorCode(), date, "Tipo de Cambio Compra USD", config);
    if (buyRate != null) {
      rates.add(buyRate);
    }

    BccrIndicatorResponse sellRate =
        fetchIndicator(config.getSellIndicatorCode(), date, "Tipo de Cambio Venta USD", config);
    if (sellRate != null) {
      rates.add(sellRate);
    }

    return rates;
  }

  /**
   * Fetches rates for a date range. Useful for backfilling historical data.
   *
   * @param fromDate start date (inclusive)
   * @param toDate end date (inclusive)
   * @return list of all indicator responses in the range
   */
  public List<BccrIndicatorResponse> fetchRatesForRange(LocalDate fromDate, LocalDate toDate) {
    BccrServiceConfiguration config = configurationService.getConfiguration();

    if (!config.isValid()) {
      log.warn("BCCR service is not properly configured. Cannot fetch exchange rates.");
      return List.of();
    }

    List<BccrIndicatorResponse> allRates = new ArrayList<>();

    List<BccrIndicatorResponse> buyRates =
        fetchIndicatorRange(
            config.getBuyIndicatorCode(), fromDate, toDate, "Tipo de Cambio Compra USD", config);
    allRates.addAll(buyRates);

    List<BccrIndicatorResponse> sellRates =
        fetchIndicatorRange(
            config.getSellIndicatorCode(), fromDate, toDate, "Tipo de Cambio Venta USD", config);
    allRates.addAll(sellRates);

    return allRates;
  }

  private BccrIndicatorResponse fetchIndicator(
      String indicatorCode, LocalDate date, String description, BccrServiceConfiguration config) {
    List<BccrIndicatorResponse> responses =
        fetchIndicatorRange(indicatorCode, date, date, description, config);
    return responses.isEmpty() ? null : responses.get(0);
  }

  private List<BccrIndicatorResponse> fetchIndicatorRange(
      String indicatorCode,
      LocalDate fromDate,
      LocalDate toDate,
      String description,
      BccrServiceConfiguration config) {

    if (!config.isValid()) {
      log.error("BCCR service configuration is invalid. Cannot fetch indicator {}.", indicatorCode);
      return List.of();
    }

    String url = buildRequestUrl(indicatorCode, fromDate, toDate, config);
    log.debug(
        "Fetching BCCR indicator {} from {} to {} using host: {}",
        indicatorCode,
        fromDate,
        toDate,
        config.getHost());

    try {
      String xmlResponse = restTemplate.getForObject(url, String.class);
      return parseXmlResponse(xmlResponse, indicatorCode, description);
    } catch (Exception e) {
      log.error(
          "Failed to fetch BCCR indicator {} for dates {} to {}: {}",
          indicatorCode,
          fromDate,
          toDate,
          e.getMessage());
      return List.of();
    }
  }

  private String buildRequestUrl(
      String indicatorCode, LocalDate fromDate, LocalDate toDate, BccrServiceConfiguration config) {
    String baseUrl = config.getHost();
    if (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }

    return UriComponentsBuilder.fromHttpUrl(baseUrl + OBTENER_INDICADORES_METHOD)
        .queryParam("Indicador", indicatorCode)
        .queryParam("FechaInicio", fromDate.format(BCCR_DATE_FORMAT))
        .queryParam("FechaFinal", toDate.format(BCCR_DATE_FORMAT))
        .queryParam("Nombre", config.getSubscriberName())
        .queryParam("SubNiveles", "N")
        .queryParam("CorreoElectronico", config.getSubscriberEmail())
        .queryParam("Token", config.getToken())
        .build()
        .toUriString();
  }

  private List<BccrIndicatorResponse> parseXmlResponse(
      String xmlResponse, String indicatorCode, String description) {
    List<BccrIndicatorResponse> responses = new ArrayList<>();

    if (xmlResponse == null || xmlResponse.isBlank()) {
      log.warn("Empty response from BCCR for indicator {}", indicatorCode);
      return responses;
    }

    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // Security: Disable external entities to prevent XXE attacks
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

      DocumentBuilder builder = factory.newDocumentBuilder();
      Document document = builder.parse(new InputSource(new StringReader(xmlResponse)));

      NodeList nodes = document.getElementsByTagName("INGRESO");

      for (int i = 0; i < nodes.getLength(); i++) {
        var node = nodes.item(i);
        var children = node.getChildNodes();

        String fecha = null;
        String valor = null;

        for (int j = 0; j < children.getLength(); j++) {
          var child = children.item(j);
          if ("FECHA".equals(child.getNodeName())) {
            fecha = child.getTextContent();
          } else if ("VALOR".equals(child.getNodeName())) {
            valor = child.getTextContent();
          }
        }

        if (fecha != null && valor != null && !valor.isBlank()) {
          try {
            LocalDate date = LocalDate.parse(fecha.trim(), BCCR_DATE_FORMAT);
            BigDecimal value = new BigDecimal(valor.trim());

            responses.add(
                BccrIndicatorResponse.builder()
                    .indicatorCode(indicatorCode)
                    .date(date)
                    .value(value)
                    .description(description)
                    .build());
          } catch (Exception e) {
            log.warn("Failed to parse BCCR response entry: fecha={}, valor={}", fecha, valor);
          }
        }
      }
    } catch (Exception e) {
      log.error("Failed to parse BCCR XML response: {}", e.getMessage());
    }

    return responses;
  }
}