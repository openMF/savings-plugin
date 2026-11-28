/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.data;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Query parameters accepted by the global credit-origination dashboard. */
@Getter
@Setter
@NoArgsConstructor
public class CreditOriginationDashboardRequest {

  @QueryParam("officeId")
  @Parameter(description = "Client office; must be inside the authenticated user's hierarchy")
  private Long officeId;

  @QueryParam("productId")
  private Long productId;

  @DefaultValue("0")
  @QueryParam("offset")
  @Parameter(description = "Offset into the stalled-application list")
  private Integer offset = 0;

  @DefaultValue("50")
  @QueryParam("limit")
  @Parameter(description = "Page size of the stalled-application list (1-200)")
  private Integer limit = 50;
}
