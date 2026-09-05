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
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Query parameters accepted by the credit-application search resource. */
@Getter
@Setter
@NoArgsConstructor
public class CreditApplicationSearchRequest {

  @QueryParam("clientId")
  private Long clientId;

  @QueryParam("officeId")
  private Long officeId;

  @QueryParam("clientTypeId")
  private Long clientTypeId;

  @QueryParam("productId")
  private Long productId;

  @QueryParam("status")
  private Integer status;

  @QueryParam("submittedFrom")
  @Parameter(description = "Inclusive submitted-on date in yyyy-MM-dd format")
  private String submittedFrom;

  @QueryParam("submittedTo")
  @Parameter(description = "Inclusive submitted-on date in yyyy-MM-dd format")
  private String submittedTo;

  @QueryParam("minAmount")
  private BigDecimal minAmount;

  @QueryParam("maxAmount")
  private BigDecimal maxAmount;

  @QueryParam("currencyCode")
  @Parameter(
      description = "ISO 4217 currency code; required when minAmount or maxAmount is supplied")
  private String currencyCode;

  @QueryParam("stateProvinceId")
  private Long stateProvinceId;

  @QueryParam("municipality")
  private String municipality;

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
