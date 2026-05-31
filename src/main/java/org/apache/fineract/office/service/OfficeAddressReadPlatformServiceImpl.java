/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.office.data.OfficeAddressData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class OfficeAddressReadPlatformServiceImpl implements OfficeAddressReadPlatformService {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformSecurityContext context;

    @Autowired
    public OfficeAddressReadPlatformServiceImpl(final PlatformSecurityContext context, final JdbcTemplate jdbcTemplate) {
        this.context = context;
        this.jdbcTemplate = jdbcTemplate;
    }

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

}
