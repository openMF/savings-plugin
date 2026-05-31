/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.data;

public final class OfficeAddressData {

    private final long officeAddressID;

    private final long officeID;

    private final long addressID;

    private final long addressTypeID;

    private final boolean isActive;

    private OfficeAddressData(final long officeAddressID, final long office_id, final long address_id, final long address_type_id,
            final boolean isActive) {
        this.officeAddressID = officeAddressID;
        this.officeID = office_id;
        this.addressID = address_id;
        this.addressTypeID = address_type_id;
        this.isActive = isActive;
    }

    public static OfficeAddressData instance(final long officeAddressID, final long office_id, final long address_id,
            final long address_type_id, final boolean isActive) {
        return new OfficeAddressData(officeAddressID, office_id, address_id, address_type_id, isActive);
    }
}
