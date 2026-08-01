package org.apache.fineract.fastpayment.sinpe.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionEditRequest;
import org.apache.fineract.fastpayment.sinpe.data.SinpeSubscriptionRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Tenant-aware SINPE external API client owned by the Savings Plugin.
 * Explicit bean name avoids clash with selfservice-plugin and fastpayment modules.
 */
@Component("savingsSinpeExternalApiClient")
@Slf4j
@RequiredArgsConstructor
public class SavingsSinpeExternalApiClient {

  private final JdbcTemplate jdbcTemplate;
  private final RestTemplate restTemplate = new RestTemplate();

  private static final String SERVICE_NAME = "SinpeService";

  private Map<String, String> getServiceProperties() {
    Map<String, String> props = new HashMap<>();
    String sql =
        "SELECT p.name, p.value FROM c_external_service_properties p "
            + "INNER JOIN c_external_service s ON p.external_service_id = s.id "
            + "WHERE s.name = ?";
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, SERVICE_NAME);
    for (Map<String, Object> row : rows) {
      String name = (String) row.get("name");
      String value = (String) row.get("value");
      if (name != null && value != null) {
        props.put(name, value);
      }
    }
    return props;
  }

  private boolean isEnabled(Map<String, String> props) {
    return "true".equalsIgnoreCase(props.get("isEnabled"));
  }

  private String getHost(Map<String, String> props) {
    return props.getOrDefault("host", "");
  }

  private HttpHeaders buildHeaders(Map<String, String> props) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String headerName = props.get("header");
    String headerValue = props.get("headerValue");
    if (headerName != null && !headerName.isBlank() && headerValue != null) {
      headers.set(headerName, headerValue);
    }
    return headers;
  }

  public void createSubscription(SinpeSubscriptionRequest request) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      log.warn("SinpeService disabled – skipping createSubscription for {}", request.getPhoneNumber());
      return;
    }
    String url = getHost(props) + "/subscription";
    HttpEntity<SinpeSubscriptionRequest> entity = new HttpEntity<>(request, buildHeaders(props));
    try {
      restTemplate.postForObject(url, entity, String.class);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create SINPE subscription: " + e.getMessage(), e);
    }
  }

  public void editSubscription(SinpeSubscriptionEditRequest request) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      log.warn("SinpeService disabled – skipping editSubscription for {}", request.getPhoneNumber());
      return;
    }
    String url = getHost(props) + "/subscription/edit";
    HttpEntity<SinpeSubscriptionEditRequest> entity =
        new HttpEntity<>(request, buildHeaders(props));
    try {
      restTemplate.postForObject(url, entity, String.class);
    } catch (Exception e) {
      throw new RuntimeException("Failed to edit SINPE subscription: " + e.getMessage(), e);
    }
  }

  public void deleteSubscription(String phoneNumber) {
    Map<String, String> props = getServiceProperties();
    if (!isEnabled(props)) {
      log.warn("SinpeService disabled – skipping deleteSubscription for {}", phoneNumber);
      return;
    }
    String url = getHost(props) + "/subscription/" + phoneNumber;
    HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(props));
    try {
      restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
    } catch (Exception e) {
      throw new RuntimeException("Failed to delete SINPE subscription: " + e.getMessage(), e);
    }
  }
}