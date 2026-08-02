package org.apache.fineract.fastpayment.sinpe.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionEditRequest;
import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionRequest;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Tenant-aware SINPE external API client owned by the Savings Plugin.
 * Uses an explicit bean name to avoid module bean clashes.
 */
@Component("savingsSinpeExternalApiClient")
@Slf4j
@RequiredArgsConstructor
public class SavingsSinpeExternalApiClient {

  /** Reads tenant external-service configuration for the SINPE provider. */
  private final JdbcTemplate jdbcTemplate;

  /** HTTP client used for the existing SINPE provider endpoints. */
  private final RestTemplate restTemplate = buildRestTemplate();

  /** External-service row name used by the existing SINPE configuration. */
  private static final String SERVICE_NAME = "SinpeService";

  /** Provider connection timeout. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** Provider read timeout. */
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

  private static RestTemplate buildRestTemplate() {
    SimpleClientHttpRequestFactory requestFactory =
        new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
    requestFactory.setReadTimeout(READ_TIMEOUT);
    return new RestTemplate(requestFactory);
  }

  private Map<String, String> getServiceProperties() {
    Map<String, String> props = new HashMap<>();
    String sql =
        "SELECT p.name, p.value FROM c_external_service_properties p "
            + "INNER JOIN c_external_service s ON p.external_service_id = s.id "
            + "WHERE s.name = ?";
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(sql, SERVICE_NAME);
    for (Map<String, Object> row : rows) {
      String name = (String) row.get("name");
      String value = (String) row.get("value");
      if (name != null && value != null) {
        props.put(name, value);
      }
    }
    return props;
  }

  private boolean isEnabled(final Map<String, String> props) {
    return "true".equalsIgnoreCase(props.get("isEnabled"));
  }

  private String getHost(final Map<String, String> props) {
    return props.getOrDefault("host", "");
  }

  private HttpHeaders buildHeaders(final Map<String, String> props) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String headerName = props.get("header");
    String headerValue = props.get("headerValue");
    if (headerName != null && !headerName.isBlank() && headerValue != null) {
      headers.set(headerName, headerValue);
    }
    return headers;
  }

  /**
   * Creates a phone subscription through the configured SINPE provider.
   *
   * @param request provider subscription payload
   * @return raw provider response body
   */
  public String createSubscription(final SinpeSubscriptionRequest request) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      log.warn(
          "SinpeService disabled – skipping createSubscription for {}",
          request.getPhoneNumber());
      return null;
    }
    String url = getHost(props) + "/subscription";
    HttpEntity<SinpeSubscriptionRequest> entity =
        new HttpEntity<>(request, buildHeaders(props));
    try {
      var response =
          restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
      log.info(
          "createSubscription HTTP status={}",
          response.getStatusCode());
      return response.getBody();
    } catch (Exception e) {
      log.warn("Failed to create SINPE subscription.", e);
      throw providerFailure("Failed to create SINPE subscription.");
    }
  }

  /**
   * Edits an existing phone subscription through the provider.
   *
   * @param request provider edit payload
   */
  public void editSubscription(final SinpeSubscriptionEditRequest request) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      log.warn(
          "SinpeService disabled – skipping editSubscription for {}",
          request.getPhoneNumber());
      return;
    }
    String url = getHost(props) + "/subscription/edit";
    HttpEntity<SinpeSubscriptionEditRequest> entity =
        new HttpEntity<>(request, buildHeaders(props));
    try {
      restTemplate.postForObject(url, entity, String.class);
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to edit SINPE subscription: " + e.getMessage(), e);
    }
  }

  /**
   * Deletes an existing phone subscription through the provider.
   *
   * @param phoneNumber linked phone number
   * @return raw provider response body
   */
  public String deleteSubscription(final String phoneNumber) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      log.warn(
          "SinpeService disabled – skipping deleteSubscription for {}",
          phoneNumber);
      return null;
    }
    String url = getHost(props) + "/subscription/" + phoneNumber;
    HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(props));
    try {
      var response =
          restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
      log.info(
          "deleteSubscription HTTP status={}",
          response.getStatusCode());
      return response.getBody();
    } catch (Exception e) {
      log.warn("Failed to delete SINPE subscription.", e);
      throw providerFailure("Failed to delete SINPE subscription.");
    }
  }

  /**
   * Retrieves the phone status from the existing provider status endpoint.
   *
   * @param phoneNumber phone number to look up
   * @return raw provider response body
   */
  public String getPhoneStatus(final String phoneNumber) {
     Map<String, String> props = getServiceProperties();
     if (!isEnabled(props)) {
       log.warn(
           "SinpeService disabled – skipping getPhoneStatus for {}",
           phoneNumber);
       return null;
     }

     String url = getHost(props) + "/phone/" + phoneNumber;
     log.info("getPhoneStatus calling GET url={}", url);

     HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(props));
     try {
       var response =
           restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
       log.info(
           "getPhoneStatus HTTP status={}",
           response.getStatusCode());
       return response.getBody();
     } catch (Exception e) {
       log.error(
           "Failed to retrieve SINPE phone status for {}",
           phoneNumber,
           e);
       throw new RuntimeException(
           "Failed to retrieve SINPE phone status: " + e.getMessage(), e);
     }
   }

  private GeneralPlatformDomainRuleException providerFailure(
      final String message) {
    return new GeneralPlatformDomainRuleException(
        "error.msg.sinpe.provider.failure", message);
  }
}
