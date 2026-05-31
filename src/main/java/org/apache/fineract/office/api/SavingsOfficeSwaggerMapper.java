/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.office.api;

import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.core.config.MapstructMapperConfig;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.organisation.office.data.OfficeData;
import org.mapstruct.Mapper;

@Mapper(config = MapstructMapperConfig.class, componentModel = "spring")
public interface SavingsOfficeSwaggerMapper {

    SavingsOfficesApiResourceSwagger.PutOfficesOfficeIdResponse toPutOfficesOfficeIdResponse(CommandProcessingResult commandProcessingResult);

    default SavingsOfficesApiResourceSwagger.PutOfficesOfficeIdResponse.PutOfficesOfficeIdResponseChanges toPutOfficesOfficeIdResponseChanges(
            Map<String, Object> changes) {
        SavingsOfficesApiResourceSwagger.PutOfficesOfficeIdResponse.PutOfficesOfficeIdResponseChanges response = new SavingsOfficesApiResourceSwagger.PutOfficesOfficeIdResponse.PutOfficesOfficeIdResponseChanges();
        Optional.ofNullable(changes).map(c -> c.get("name")).ifPresent(c -> response.name = String.valueOf(c));
        return response;
    }

    SavingsOfficesApiResourceSwagger.GetOfficesResponse toGetOfficesResponse(OfficeData officeData);
}
