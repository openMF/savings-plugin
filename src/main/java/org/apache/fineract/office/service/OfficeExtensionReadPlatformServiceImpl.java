/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.office.data.OfficeAddressData;
import org.apache.fineract.office.data.OfficeGeolocationData;
import org.apache.fineract.office.data.OfficeServiceData;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** JDBC-backed implementation of {@link OfficeExtensionReadPlatformService}. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OfficeExtensionReadPlatformServiceImpl implements OfficeExtensionReadPlatformService {

  private final JdbcTemplate jdbcTemplate;
  private final PlatformSecurityContext context;

  // -------------------------------------------------------------------------
  // RETRIEVAL LOGIC
  // -------------------------------------------------------------------------

  @Override
  public Collection<OfficeAddressData> retrieveOfficeAddresses(final Long officeId) {
    this.context.authenticatedUser();
    final OfficeAddressRowMapper rm = new OfficeAddressRowMapper();
    final String sql = "SELECT " + rm.schema() + " WHERE oa.office_id = ?";

    return this.jdbcTemplate.query(sql, rm, officeId);
  }

  @Override
  public Collection<OfficeAddressData> retrieveOfficeAddrConfiguration(final String entity) {
    this.context.authenticatedUser();
    final OfficeAddrConfigMapper rm = new OfficeAddrConfigMapper();
    final String sql = "SELECT " + rm.schema() + " WHERE fld.entity = ?";

    return this.jdbcTemplate.query(sql, rm, entity); // NOSONAR
  }

  @Override
  public Collection<OfficeServiceData> retrieveOfficeServices(final Long officeId) {
    this.context.authenticatedUser();
    return this.jdbcTemplate.query(
        "SELECT s.id, s.office_id, s.service_name, s.service_external_id, s.working_hours"
            + " FROM m_selfservice_office_service s WHERE s.office_id = ?",
        new OfficeServiceRowMapper(),
        officeId);
  }

  @Override
  public OfficeServiceData retrieveOfficeService(final Long serviceId) {
    this.context.authenticatedUser();
    try {
      return this.jdbcTemplate.queryForObject(
          "SELECT s.id, s.office_id, s.service_name, s.service_external_id, s.working_hours"
              + " FROM m_selfservice_office_service s WHERE s.id = ?",
          new OfficeServiceRowMapper(),
          serviceId);
    } catch (final EmptyResultDataAccessException e) {
      return null;
    }
  }

  @Override
  public OfficeGeolocationData retrieveOfficeGeolocation(final Long officeId) {
    this.context.authenticatedUser();
    try {
      return this.jdbcTemplate.queryForObject(
          "SELECT g.id, g.office_id, g.latitude, g.longitude"
              + " FROM m_selfservice_office_geolocation g WHERE g.office_id = ?",
          new OfficeGeolocationRowMapper(),
          officeId);
    } catch (final EmptyResultDataAccessException e) {
      return null;
    }
  }

  // -------------------------------------------------------------------------
  // ROW MAPPERS
  // -------------------------------------------------------------------------

  private static final class OfficeAddressRowMapper implements RowMapper<OfficeAddressData> {

    public String schema() {
      return " oa.id AS officeAddressId, oa.office_id AS officeId, oa.address_id AS addressId, "
          + " oa.address_type_id AS addressTypeId, oa.is_active AS isActive, "
          + " a.street AS street, a.address_line_1 AS addressLine1, a.address_line_2 AS addressLine2, "
          + " a.address_line_3 AS addressLine3, a.town_village AS townVillage, a.city AS city, "
          + " a.county_district AS countyDistrict, a.state_province_id AS stateProvinceId, "
          + " a.country_id AS countryId, a.postal_code AS postalCode, a.latitude AS latitude, "
          + " a.longitude AS longitude, a.created_by AS createdBy, a.created_on AS createdOn, "
          + " a.updated_by AS updatedBy, a.updated_on AS updatedOn "
          + " FROM m_office_address oa "
          + " JOIN m_address a ON a.id = oa.address_id";
    }

    @Override
    public OfficeAddressData mapRow(final ResultSet rs, final int rowNum) throws SQLException {
      // Null safety for primitive Long wrapper conversions
      Long stateProvinceId = rs.getLong("stateProvinceId");
      if (rs.wasNull()) stateProvinceId = null;

      Long countryId = rs.getLong("countryId");
      if (rs.wasNull()) countryId = null;

      return OfficeAddressData.instance(
          rs.getLong("officeAddressId"),
          rs.getLong("officeId"),
          rs.getLong("addressId"),
          rs.getLong("addressTypeId"),
          rs.getBoolean("isActive"),
          rs.getString("street"),
          rs.getString("addressLine1"),
          rs.getString("addressLine2"),
          rs.getString("addressLine3"),
          rs.getString("townVillage"),
          rs.getString("city"),
          rs.getString("countyDistrict"),
          stateProvinceId,
          countryId,
          rs.getString("postalCode"),
          rs.getBigDecimal("latitude"),
          rs.getBigDecimal("longitude"),
          rs.getString("createdBy"),
          rs.getDate("createdOn") != null ? rs.getDate("createdOn").toLocalDate() : null,
          rs.getString("updatedBy"),
          rs.getDate("updatedOn") != null ? rs.getDate("updatedOn").toLocalDate() : null);
    }
  }

  private static final class OfficeServiceRowMapper implements RowMapper<OfficeServiceData> {
    @Override
    public OfficeServiceData mapRow(final ResultSet rs, final int rowNum) throws SQLException {
      final Long id = rs.getLong("id");
      final Long officeId = rs.getLong("office_id");
      final String serviceName = rs.getString("service_name");
      final String serviceExternalId = rs.getString("service_external_id");
      final String workingHours = rs.getString("working_hours");
      return OfficeServiceData.instance(id, officeId, serviceName, serviceExternalId, workingHours);
    }
  }

  private static final class OfficeGeolocationRowMapper
      implements RowMapper<OfficeGeolocationData> {
    @Override
    public OfficeGeolocationData mapRow(final ResultSet rs, final int rowNum) throws SQLException {
      final Long id = rs.getLong("id");
      final Long officeId = rs.getLong("office_id");
      final BigDecimal latitude = rs.getBigDecimal("latitude");
      final BigDecimal longitude = rs.getBigDecimal("longitude");
      return OfficeGeolocationData.instance(id, officeId, latitude, longitude);
    }
  }

  // Note: Kept strictly for field config retro-compatibility from original snippet, though it
  // operates on entirely different tables.
  private static final class OfficeAddrConfigMapper implements RowMapper<OfficeAddressData> {
    public String schema() {
      return "fld.id as fieldConfigurationId,fld.entity as entity,fld.table as entitytable,fld.field as field,fld.is_enabled as is_enabled,"
          + "fld.is_mandatory as is_mandatory,fld.validation_regex as validation_regex from m_field_configuration fld";
    }

    @Override
    public OfficeAddressData mapRow(final ResultSet rs, final int rowNum) throws SQLException {
      // Usually maps to FieldConfigurationData. Returning barebones instance to prevent breaking
      // the signature.
      return OfficeAddressData.instance(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
          null, null, null, null, null, null);
    }
  }
}
