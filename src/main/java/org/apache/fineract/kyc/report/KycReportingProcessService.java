/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.report;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.dataqueries.data.ReportExportType;
import org.apache.fineract.infrastructure.report.annotation.ReportService;
import org.apache.fineract.infrastructure.report.service.ReportingProcessService;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.service.ClientIdentifierReadPlatformService;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.springframework.context.annotation.Fallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Native PDF implementation of the two report names used by Personal Data's Export KYC action. */
@Service
@Fallback
@ReportService(type = "KYC")
public class KycReportingProcessService implements ReportingProcessService {
  private final PlatformSecurityContext context;
  private final ClientReadPlatformService clients;
  private final ClientIdentifierReadPlatformService identifiers;
  private final JdbcTemplate jdbc;
  private final KycPdfRenderer renderer;

  public KycReportingProcessService(
      PlatformSecurityContext context,
      ClientReadPlatformService clients,
      ClientIdentifierReadPlatformService identifiers,
      JdbcTemplate jdbc,
      KycPdfRenderer renderer) {
    this.context = context;
    this.clients = clients;
    this.identifiers = identifiers;
    this.jdbc = jdbc;
    this.renderer = renderer;
  }

  @Override
  public Response processRequest(String reportName, MultivaluedMap<String, String> queryParams) {
    var user = context.authenticatedUser();
    user.validateHasReadPermission("CLIENT");
    if (user.hasNotPermissionForReport(reportName)) {
      throw new NoAuthorizationException("Not authorized to read this report");
    }
    Map<String, String> params = getReportParams(reportName, queryParams);
    long clientId = Long.parseLong(params.get("${clientId}"));
    // Reuse core tenant routing and office/transfer hierarchy enforcement before reading any KYC
    // data.
    ClientData client = clients.retrieveOne(clientId);
    Long legalForm = client.getLegalForm() == null ? null : client.getLegalForm().getId();
    boolean entity = "KYCLegalPerson".equals(reportName);
    if (legalForm == null || legalForm.longValue() != (entity ? 2 : 1)) {
      throw new BadRequestException("Report does not match the client's legal form");
    }
    boolean canReadIdentities =
        !user.hasNotPermissionForAnyOf(
            "ALL_FUNCTIONS", "ALL_FUNCTIONS_READ", "READ_CLIENTIDENTIFIER");
    var identityData = canReadIdentities ? identifiers.retrieveClientIdentifiers(clientId) : null;
    // The routing JdbcTemplate stays in the authenticated tenant; IDs are bound, never
    // interpolated.
    var verifications =
        jdbc.queryForList(
            "SELECT kyc_status, created_on_utc FROM m_client_kyc_verification WHERE client_id = ? "
                + "ORDER BY created_on_utc DESC, id DESC",
            clientId);
    String table = entity ? "dt_kyc_validation_entity" : "dt_kyc_validation";
    boolean canReadValidation = !user.hasNotPermissionForDatatable(table, "READ");
    Map<String, Object> validation = Map.of();
    if (canReadValidation) {
      Integer registered =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM x_registered_table WHERE registered_table_name = ? "
                  + "AND application_table_name = 'm_client'",
              Integer.class,
              table);
      if (registered != null && registered > 0) {
        // table is one of two constants above, never user input.
        var records =
            jdbc.queryForList("SELECT * FROM " + table + " WHERE client_id = ?", clientId);
        if (!records.isEmpty()) {
          validation = records.getFirst();
        }
      }
    }
    byte[] pdf =
        renderer.render(
            client,
            identityData,
            verifications.isEmpty() ? Map.of() : verifications.getFirst(),
            validation,
            canReadValidation);
    return Response.ok(pdf, "application/pdf")
        .header("Content-Disposition", "inline; filename=\"KYC-" + clientId + ".pdf\"")
        .header("Cache-Control", "no-store, private")
        .header("X-Content-Type-Options", "nosniff")
        .build();
  }

  @Override
  public List<ReportExportType> getAvailableExportTargets() {
    return List.of(new ReportExportType("PDF", "PDF"));
  }

  @Override
  public Map<String, String> getReportParams(String name, MultivaluedMap<String, String> params) {
    if (!List.of("KYCNaturalPerson", "KYCLegalPerson").contains(name)) {
      throw new NotFoundException("Unknown KYC report");
    }
    if (params.get("R_clientId") == null
        || params.get("R_clientId").size() != 1
        || params.getFirst("R_clientId") == null
        || !params.getFirst("R_clientId").matches("[1-9][0-9]{0,18}")) {
      throw new BadRequestException("A single positive R_clientId is required");
    }
    try {
      Long.parseLong(params.getFirst("R_clientId"));
    } catch (NumberFormatException e) {
      throw new BadRequestException("Invalid R_clientId");
    }
    if (params.keySet().stream()
        .anyMatch(key -> key.startsWith("R_") && !key.equals("R_clientId"))) {
      throw new BadRequestException("Unknown report parameter");
    }
    if (params.get("output-type") != null
        && (params.get("output-type").size() != 1
            || !"PDF".equalsIgnoreCase(params.getFirst("output-type")))) {
      throw new BadRequestException("KYC reports support PDF output only");
    }
    return Map.of("${clientId}", params.getFirst("R_clientId"));
  }
}
