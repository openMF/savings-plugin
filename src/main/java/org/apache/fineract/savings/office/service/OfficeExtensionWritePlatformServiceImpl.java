/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.savings.office.service;

import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.exception.OfficeNotFoundException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC-backed implementation of {@link OfficeExtensionWritePlatformService} for managing office
 * services and geolocation data.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class OfficeExtensionWritePlatformServiceImpl
    implements OfficeExtensionWritePlatformService {

  private static final String RESOURCE_NAME = "officeService";
  private static final BigDecimal LAT_MIN = new BigDecimal("-90");
  private static final BigDecimal LAT_MAX = new BigDecimal("90");
  private static final BigDecimal LNG_MIN = new BigDecimal("-180");
  private static final BigDecimal LNG_MAX = new BigDecimal("180");

  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedJdbcTemplate;
  private final PlatformSecurityContext context;
  private final FromJsonHelper fromJsonHelper;

  /** {@inheritDoc} */
  @Override
  public CommandProcessingResult createOfficeService(final Long officeId, final String jsonBody) {
    this.context.authenticatedUser();
    validateOfficeExists(officeId);

    final JsonElement element = this.fromJsonHelper.parse(jsonBody);
    final String serviceName = this.fromJsonHelper.extractStringNamed("serviceName", element);
    final String serviceExternalId =
        this.fromJsonHelper.extractStringNamed("serviceExternalId", element);
    final String workingHours = this.fromJsonHelper.extractStringNamed("workingHours", element);

    final MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("officeId", officeId);
    params.addValue("serviceName", serviceName);
    params.addValue("serviceExternalId", serviceExternalId);
    params.addValue("workingHours", workingHours);

    final KeyHolder keyHolder = new GeneratedKeyHolder();
    this.namedJdbcTemplate.update(
        "INSERT INTO m_selfservice_office_service (office_id, service_name, service_external_id, working_hours)"
            + " VALUES (:officeId, :serviceName, :serviceExternalId, :workingHours)",
        params,
        keyHolder,
        new String[] {"id"});

    final Long serviceId = keyHolder.getKey().longValue();
    return new CommandProcessingResultBuilder()
        .withEntityId(serviceId)
        .withOfficeId(officeId)
        .build();
  }

  /** {@inheritDoc} */
  @Override
  public CommandProcessingResult updateOfficeService(
      final Long officeId, final Long serviceId, final String jsonBody) {
    this.context.authenticatedUser();

    final JsonElement element = this.fromJsonHelper.parse(jsonBody);
    final Map<String, Object> changes = new HashMap<>();

    final StringBuilder sql = new StringBuilder("UPDATE m_selfservice_office_service SET ");
    final MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("serviceId", serviceId);
    params.addValue("officeId", officeId);
    boolean first = true;

    if (this.fromJsonHelper.parameterExists("serviceName", element)) {
      final String serviceName = this.fromJsonHelper.extractStringNamed("serviceName", element);
      sql.append("service_name = :serviceName");
      params.addValue("serviceName", serviceName);
      changes.put("serviceName", serviceName);
      first = false;
    }
    if (this.fromJsonHelper.parameterExists("serviceExternalId", element)) {
      if (!first) {
        sql.append(", ");
      }
      final String serviceExternalId =
          this.fromJsonHelper.extractStringNamed("serviceExternalId", element);
      sql.append("service_external_id = :serviceExternalId");
      params.addValue("serviceExternalId", serviceExternalId);
      changes.put("serviceExternalId", serviceExternalId);
      first = false;
    }
    if (this.fromJsonHelper.parameterExists("workingHours", element)) {
      if (!first) {
        sql.append(", ");
      }
      final String workingHours = this.fromJsonHelper.extractStringNamed("workingHours", element);
      sql.append("working_hours = :workingHours");
      params.addValue("workingHours", workingHours);
      changes.put("workingHours", workingHours);
    }
    sql.append(" WHERE id = :serviceId AND office_id = :officeId");

    if (!changes.isEmpty()) {
      final int affectedRows = this.namedJdbcTemplate.update(sql.toString(), params);
      if (affectedRows == 0) {
        throw new PlatformDataIntegrityException(
            "error.msg.office.service.not.found",
            "Office service with id " + serviceId + " not found for office " + officeId,
            "serviceId",
            serviceId);
      }
    }

    return new CommandProcessingResultBuilder()
        .withEntityId(serviceId)
        .withOfficeId(officeId)
        .with(changes)
        .build();
  }

  /** {@inheritDoc} */
  @Override
  public CommandProcessingResult deleteOfficeService(final Long officeId, final Long serviceId) {
    this.context.authenticatedUser();
    final int affectedRows =
        this.jdbcTemplate.update(
            "DELETE FROM m_selfservice_office_service WHERE id = ? AND office_id = ?",
            serviceId,
            officeId);
    if (affectedRows == 0) {
      throw new PlatformDataIntegrityException(
          "error.msg.office.service.not.found",
          "Office service with id " + serviceId + " not found for office " + officeId,
          "serviceId",
          serviceId);
    }
    return new CommandProcessingResultBuilder()
        .withEntityId(serviceId)
        .withOfficeId(officeId)
        .build();
  }

  /** {@inheritDoc} */
  @Override
  public CommandProcessingResult saveOfficeGeolocation(final Long officeId, final String jsonBody) {
    this.context.authenticatedUser();
    validateOfficeExists(officeId);

    final JsonElement element = this.fromJsonHelper.parse(jsonBody);
    final BigDecimal latitude =
        this.fromJsonHelper.extractBigDecimalWithLocaleNamed("latitude", element);
    final BigDecimal longitude =
        this.fromJsonHelper.extractBigDecimalWithLocaleNamed("longitude", element);

    validateCoordinates(latitude, longitude);

    final MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("officeId", officeId);
    params.addValue("latitude", latitude);
    params.addValue("longitude", longitude);

    this.namedJdbcTemplate.update(
        "INSERT INTO m_selfservice_office_geolocation (office_id, latitude, longitude)"
            + " VALUES (:officeId, :latitude, :longitude)"
            + " ON CONFLICT (office_id) DO UPDATE SET latitude = EXCLUDED.latitude,"
            + " longitude = EXCLUDED.longitude",
        params);

    final Long geolocationId =
        this.jdbcTemplate.queryForObject(
            "SELECT id FROM m_selfservice_office_geolocation WHERE office_id = ?",
            Long.class,
            officeId);

    return new CommandProcessingResultBuilder()
        .withEntityId(geolocationId)
        .withOfficeId(officeId)
        .build();
  }

  /** {@inheritDoc} */
  @Override
  public CommandProcessingResult deleteOfficeGeolocation(final Long officeId) {
    this.context.authenticatedUser();
    this.jdbcTemplate.update(
        "DELETE FROM m_selfservice_office_geolocation WHERE office_id = ?", officeId);
    return new CommandProcessingResultBuilder().withOfficeId(officeId).build();
  }

  private void validateOfficeExists(final Long officeId) {
    try {
      this.jdbcTemplate.queryForObject(
          "SELECT 1 FROM m_office WHERE id = ?", Integer.class, officeId);
    } catch (final EmptyResultDataAccessException e) {
      throw new OfficeNotFoundException(officeId, e);
    }
  }

  private void validateCoordinates(final BigDecimal latitude, final BigDecimal longitude) {
    final List<ApiParameterError> errors = new ArrayList<>();
    final DataValidatorBuilder validator = new DataValidatorBuilder(errors).resource(RESOURCE_NAME);

    validator.parameter("latitude").value(latitude).notNull();
    validator.parameter("longitude").value(longitude).notNull();

    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }

    if (latitude.compareTo(LAT_MIN) < 0 || latitude.compareTo(LAT_MAX) > 0) {
      errors.add(
          ApiParameterError.parameterError(
              "validation.msg.office.geolocation.latitude.out.of.range",
              "Latitude must be between -90 and 90",
              "latitude",
              latitude));
    }
    if (longitude.compareTo(LNG_MIN) < 0 || longitude.compareTo(LNG_MAX) > 0) {
      errors.add(
          ApiParameterError.parameterError(
              "validation.msg.office.geolocation.longitude.out.of.range",
              "Longitude must be between -180 and 180",
              "longitude",
              longitude));
    }
    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }
  }
}
