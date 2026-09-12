/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.data;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ProspectSearchRequest {

  @QueryParam("q")
  @Parameter(description = "Case-insensitive search over prospect displayName and externalRef")
  private String q;

  @QueryParam("officeId")
  private Long officeId;

  @QueryParam("registrationStatus")
  private String registrationStatus;

  @QueryParam("createdFrom")
  @Parameter(description = "Inclusive technical created date in yyyy-MM-dd format")
  private String createdFrom;

  @QueryParam("createdTo")
  @Parameter(description = "Inclusive technical created date in yyyy-MM-dd format")
  private String createdTo;

  @QueryParam("clientId")
  private Long clientId;

  @QueryParam("lastCompletedStageCode")
  private String lastCompletedStageCode;

  @DefaultValue("0")
  @QueryParam("offset")
  private Integer offset = 0;

  @DefaultValue("50")
  @QueryParam("limit")
  private Integer limit = 50;

  @QueryParam("orderBy")
  private String orderBy;

  @QueryParam("sortOrder")
  private String sortOrder;
}
