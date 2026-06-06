/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.service;

import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.time.LocalDate;
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
  
  @Override
    public CommandProcessingResult createOfficeAddress(final Long officeId, final String jsonBody) {
        this.context.authenticatedUser();
        validateOfficeExists(officeId);

        final JsonElement element = this.fromJsonHelper.parse(jsonBody);

        // extract address fields (all optional except street / addressLine1 maybe; handle nulls)
        final String street = this.fromJsonHelper.extractStringNamed("street", element);
        final String addressLine1 = this.fromJsonHelper.extractStringNamed("addressLine1", element);
        final String addressLine2 = this.fromJsonHelper.extractStringNamed("addressLine2", element);
        final String addressLine3 = this.fromJsonHelper.extractStringNamed("addressLine3", element);
        final String townVillage = this.fromJsonHelper.extractStringNamed("townVillage", element);
        final String city = this.fromJsonHelper.extractStringNamed("city", element);
        final String countyDistrict = this.fromJsonHelper.extractStringNamed("countyDistrict", element);
        final String postalCode = this.fromJsonHelper.extractStringNamed("postalCode", element);
        final BigDecimal latitude = this.fromJsonHelper.extractBigDecimalWithLocaleNamed("latitude", element);
        final BigDecimal longitude = this.fromJsonHelper.extractBigDecimalWithLocaleNamed("longitude", element);
        final Long stateProvinceId = this.fromJsonHelper.extractLongNamed("stateProvinceId", element);
        final Long countryId = this.fromJsonHelper.extractLongNamed("countryId", element);

        final Long addressTypeId = this.fromJsonHelper.extractLongNamed("addressTypeId", element);
        // default is_active to true if not provided
        final Boolean isActive = this.fromJsonHelper.extractBooleanNamed("isActive", element) ?
                this.fromJsonHelper.extractBooleanNamed("isActive", element) : Boolean.TRUE;

        final String username = this.context.authenticatedUser().getUsername();
        final LocalDate now = LocalDate.now();

        // insert into m_address
        final MapSqlParameterSource addressParams = new MapSqlParameterSource();
        addressParams.addValue("street", street);
        addressParams.addValue("addressLine1", addressLine1);
        addressParams.addValue("addressLine2", addressLine2);
        addressParams.addValue("addressLine3", addressLine3);
        addressParams.addValue("townVillage", townVillage);
        addressParams.addValue("city", city);
        addressParams.addValue("countyDistrict", countyDistrict);
        addressParams.addValue("stateProvinceId", stateProvinceId);
        addressParams.addValue("countryId", countryId);
        addressParams.addValue("postalCode", postalCode);
        addressParams.addValue("latitude", latitude);
        addressParams.addValue("longitude", longitude);
        addressParams.addValue("createdBy", username);
        addressParams.addValue("createdOn", now);
        addressParams.addValue("updatedBy", username);
        addressParams.addValue("updatedOn", now);

        final KeyHolder addressKeyHolder = new GeneratedKeyHolder();
        this.namedJdbcTemplate.update(
                "INSERT INTO m_address (street, address_line_1, address_line_2, address_line_3,"
                + " town_village, city, county_district, state_province_id, country_id,"
                + " postal_code, latitude, longitude, created_by, created_on, updated_by, updated_on)"
                + " VALUES (:street, :addressLine1, :addressLine2, :addressLine3, :townVillage,"
                + " :city, :countyDistrict, :stateProvinceId, :countryId, :postalCode,"
                + " :latitude, :longitude, :createdBy, :createdOn, :updatedBy, :updatedOn)",
                addressParams, addressKeyHolder, new String[]{"id"});
        final Long addressId = addressKeyHolder.getKey().longValue();

        // insert into m_office_address
        final MapSqlParameterSource officeAddressParams = new MapSqlParameterSource();
        officeAddressParams.addValue("officeId", officeId);
        officeAddressParams.addValue("addressId", addressId);
        officeAddressParams.addValue("addressTypeId", addressTypeId);
        officeAddressParams.addValue("isActive", isActive);

        final KeyHolder officeAddressKeyHolder = new GeneratedKeyHolder();
        this.namedJdbcTemplate.update(
                "INSERT INTO m_office_address (office_id, address_id, address_type_id, is_active)"
                + " VALUES (:officeId, :addressId, :addressTypeId, :isActive)",
                officeAddressParams, officeAddressKeyHolder, new String[]{"id"});
        final Long officeAddressId = officeAddressKeyHolder.getKey().longValue();

        return new CommandProcessingResultBuilder()
                .withEntityId(officeAddressId)
                .withOfficeId(officeId)
                .build();
    }

    @Override
    public CommandProcessingResult updateOfficeAddress(final Long officeId, final Long serviceId, final String jsonBody) {
        this.context.authenticatedUser();

        // Retrieve the existing mapping to get the address_id
        final Map<String, Object> existingMapping;
        try {
            existingMapping = this.jdbcTemplate.queryForMap(
                    "SELECT address_id, address_type_id, is_active FROM m_office_address"
                    + " WHERE id = ? AND office_id = ?", serviceId, officeId);
        } catch (EmptyResultDataAccessException e) {
            throw new PlatformDataIntegrityException(
                    "error.msg.office.address.not.found",
                    "Office address mapping with id " + serviceId + " not found for office " + officeId,
                    "serviceId", serviceId);
        }
        final Long addressId = (Long) existingMapping.get("address_id");

        final JsonElement element = this.fromJsonHelper.parse(jsonBody);
        final Map<String, Object> changes = new HashMap<>();

        // --- update address fields in m_address if any are provided ---
        final StringBuilder addressSql = new StringBuilder("UPDATE m_address SET ");
        final MapSqlParameterSource addressParams = new MapSqlParameterSource();
        addressParams.addValue("addressId", addressId);
        boolean first = true;

        if (this.fromJsonHelper.parameterExists("street", element)) {
            final String val = this.fromJsonHelper.extractStringNamed("street", element);
            addressSql.append("street = :street");
            addressParams.addValue("street", val);
            changes.put("street", val);
            first = false;
        }
        if (this.fromJsonHelper.parameterExists("addressLine1", element)) {
            if (!first) addressSql.append(", ");
            final String val = this.fromJsonHelper.extractStringNamed("addressLine1", element);
            addressSql.append("address_line_1 = :addressLine1");
            addressParams.addValue("addressLine1", val);
            changes.put("addressLine1", val);
            first = false;
        }
        if (this.fromJsonHelper.parameterExists("addressLine2", element)) {
            if (!first) addressSql.append(", ");
            final String val = this.fromJsonHelper.extractStringNamed("addressLine2", element);
            addressSql.append("address_line_2 = :addressLine2");
            addressParams.addValue("addressLine2", val);
            changes.put("addressLine2", val);
            first = false;
        }
        if (this.fromJsonHelper.parameterExists("addressLine3", element)) {
            if (!first) addressSql.append(", ");
            final String val = this.fromJsonHelper.extractStringNamed("addressLine3", element);
            addressSql.append("address_line_3 = :addressLine3");
            addressParams.addValue("addressLine3", val);
            changes.put("addressLine3", val);
            first = false;
        }
        if (this.fromJsonHelper.parameterExists("townVillage", element)) {
            if (!first) addressSql.append(", ");
            final String val = this.fromJsonHelper.extractStringNamed("townVillage", element);
            addressSql.append("town_village = :townVillage");
            addressParams.addValue("townVillage", val);
            changes.put("townVillage", val);
            first = false;
        }
        if (this.fromJsonHelper.parameterExists("city", element)) {
            if (!first) addressSql.append(", ");
            final String val = this.fromJsonHelper.extractStringNamed("city", element);
            addressSql.append("city = :city");
            addressParams.addValue("city", val);
            changes.put("city", val);
            first = false;
        }
        if (this.fromJsonHelper.parameterExists("countyDistrict", element)) {
            if (!first) addressSql.append(", ");
            final String val = this.fromJsonHelper.extractStringNamed("countyDistrict", element);
            addressSql.append("county_district = :countyDistrict");
            addressParams.addValue("countyDistrict", val);
            changes.put("countyDistrict", val);
            first = false;
        }
        if (this.fromJsonHelper.parameterExists("postalCode", element)) {
            if (!first) addressSql.append(", ");
            final String val = this.fromJsonHelper.extractStringNamed("postalCode", element);
            addressSql.append("postal_code = :postalCode");
            addressParams.addValue("postalCode", val);
            changes.put("postalCode", val);
            first = false;
        }
        if (this.fromJsonHelper.parameterExists("latitude", element)) {
            if (!first) addressSql.append(", ");
            final BigDecimal val = this.fromJsonHelper.extractBigDecimalWithLocaleNamed("latitude", element);
            addressSql.append("latitude = :latitude");
            addressParams.addValue("latitude", val);
            changes.put("latitude", val);
            first = false;
        }
        if (this.fromJsonHelper.parameterExists("longitude", element)) {
            if (!first) addressSql.append(", ");
            final BigDecimal val = this.fromJsonHelper.extractBigDecimalWithLocaleNamed("longitude", element);
            addressSql.append("longitude = :longitude");
            addressParams.addValue("longitude", val);
            changes.put("longitude", val);
            first = false;
        }
        if (this.fromJsonHelper.parameterExists("stateProvinceId", element)) {
            if (!first) addressSql.append(", ");
            final Long val = this.fromJsonHelper.extractLongNamed("stateProvinceId", element);
            addressSql.append("state_province_id = :stateProvinceId");
            addressParams.addValue("stateProvinceId", val);
            changes.put("stateProvinceId", val);
            first = false;
        }
        if (this.fromJsonHelper.parameterExists("countryId", element)) {
            if (!first) addressSql.append(", ");
            final Long val = this.fromJsonHelper.extractLongNamed("countryId", element);
            addressSql.append("country_id = :countryId");
            addressParams.addValue("countryId", val);
            changes.put("countryId", val);
            first = false;
        }
        // Always update updated_by and updated_on for address
        if (!first) addressSql.append(", ");
        addressSql.append("updated_by = :updatedBy, updated_on = :updatedOn");
        addressParams.addValue("updatedBy", this.context.authenticatedUser().getUsername());
        addressParams.addValue("updatedOn", LocalDate.now());

        addressSql.append(" WHERE id = :addressId");

        if (changes.containsKey("street") || changes.containsKey("addressLine1")
                || changes.containsKey("addressLine2") || changes.containsKey("addressLine3")
                || changes.containsKey("townVillage") || changes.containsKey("city")
                || changes.containsKey("countyDistrict") || changes.containsKey("postalCode")
                || changes.containsKey("latitude") || changes.containsKey("longitude")
                || changes.containsKey("stateProvinceId") || changes.containsKey("countryId")) {
            this.namedJdbcTemplate.update(addressSql.toString(), addressParams);
        }

        // --- update office_address link (type and active flag) ---
        final StringBuilder linkSql = new StringBuilder("UPDATE m_office_address SET ");
        final MapSqlParameterSource linkParams = new MapSqlParameterSource();
        linkParams.addValue("serviceId", serviceId);
        linkParams.addValue("officeId", officeId);
        boolean linkFirst = true;

        if (this.fromJsonHelper.parameterExists("addressTypeId", element)) {
            final Long val = this.fromJsonHelper.extractLongNamed("addressTypeId", element);
            linkSql.append("address_type_id = :addressTypeId");
            linkParams.addValue("addressTypeId", val);
            changes.put("addressTypeId", val);
            linkFirst = false;
        }
        if (this.fromJsonHelper.parameterExists("isActive", element)) {
            if (!linkFirst) linkSql.append(", ");
            final Boolean val = this.fromJsonHelper.extractBooleanNamed("isActive", element);
            linkSql.append("is_active = :isActive");
            linkParams.addValue("isActive", val);
            changes.put("isActive", val);
            linkFirst = false;
        }
        linkSql.append(" WHERE id = :serviceId AND office_id = :officeId");

        if (!linkFirst) { // something to update in link
            this.namedJdbcTemplate.update(linkSql.toString(), linkParams);
        }

        if (changes.isEmpty()) {
            throw new RuntimeException("validation.msg.office.address.no.fields");
        }

        return new CommandProcessingResultBuilder()
                .withEntityId(serviceId)
                .withOfficeId(officeId)
                .with(changes)
                .build();
    }

    @Override
    public CommandProcessingResult deleteOfficeAddress(final Long officeId, final Long serviceId) {
        this.context.authenticatedUser();
        final int affected = this.jdbcTemplate.update(
                "DELETE FROM m_office_address WHERE id = ? AND office_id = ?",
                serviceId, officeId);
        if (affected == 0) {
            throw new PlatformDataIntegrityException(
                    "error.msg.office.address.not.found",
                    "Office address with id " + serviceId + " not found for office " + officeId,
                    "serviceId", serviceId);
        }
        // Optionally, delete the parent m_address row if it's no longer referenced.
        // For simplicity we leave it; a separate cleanup could be done.
        return new CommandProcessingResultBuilder()
                .withEntityId(serviceId)
                .withOfficeId(officeId)
                .build();
    }
  
  
}
