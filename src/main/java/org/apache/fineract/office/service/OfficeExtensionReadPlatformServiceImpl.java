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
  
  private static final class OfficeAddrMapper implements RowMapper<OfficeAddressData> {

    public String schema() {
        return "fld.id as fieldConfigurationId,fld.entity as entity,fld.table as entitytable,fld.field as field,fld.is_enabled as is_enabled,"
                + "fld.is_mandatory as is_mandatory,fld.validation_regex as validation_regex from m_field_configuration fld";
    }

    @Override
    public OfficeAddressData mapRow(final ResultSet rs, @SuppressWarnings("unused") final int rowNum) throws SQLException {
        final long officeAddressId = rs.getLong("officeAddressId");
        final long office_id = rs.getLong("office_id");
        final long address_id = rs.getLong("address_id");
        final long address_type_id = rs.getLong("address_type_id");
        final boolean is_active = rs.getBoolean("is_active");

        return OfficeAddressData.instance(officeAddressId, office_id, address_id, address_type_id, is_active);

        }
    }

    @Override
    public Collection<OfficeAddressData> retrieveOfficeAddrConfiguration(final String entity) {
        this.context.authenticatedUser();

        final OfficeAddrMapper rm = new OfficeAddrMapper();
        final String sql = "select " + rm.schema() + " where fld.entity=?";

        return this.jdbcTemplate.query(sql, rm, entity); // NOSONAR
    }

  /** {@inheritDoc} */
  @Override
  public Collection<OfficeServiceData> retrieveOfficeServices(final Long officeId) {
    this.context.authenticatedUser();
    return this.jdbcTemplate.query(
        "SELECT s.id, s.office_id, s.service_name, s.service_external_id, s.working_hours"
            + " FROM m_selfservice_office_service s WHERE s.office_id = ?",
        new OfficeServiceRowMapper(),
        officeId);
  }

  /** {@inheritDoc} */
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

  /** {@inheritDoc} */
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
  
  
  
}
