/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Diagnostic API. The only command saves immutable plugin evidence; there is no financial command.
 */
@Path("/v1/loans/anpa-writeoff")
@Component
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "ANPA accounting",
    description = "Read-only loan accounting diagnostics and immutable assessments")
@RequiredArgsConstructor
public class AnpaAccountingApiResource {
  private final AnpaAccountingService service;
  private final AnpaAssessmentAuditService assessments;
  private final AnpaAccountingPolicyService policies;
  private final AnpaCorrectionService corrections;

  @GET
  @Path("/template")
  public Response template() {
    return invoke(service::template);
  }

  @GET
  @Path("/loans/{loanId}/eligibility")
  public Response eligibility(@PathParam("loanId") long id) {
    return invoke(() -> service.eligibility(id));
  }

  @GET
  @Path("/loans/{loanId}/history")
  public Response history(@PathParam("loanId") long id) {
    return invoke(() -> service.history(id));
  }

  @GET
  @Path("/loans/{loanId}/receivables")
  public Response receivables(@PathParam("loanId") long id) {
    return invoke(() -> service.receivables(id));
  }

  @GET
  @Path("/loans/{loanId}/preview")
  public Response preview(@PathParam("loanId") long id, @QueryParam("asOf") String asOf) {
    return invoke(() -> service.preview(id, asOf));
  }

  @POST
  @Path("/loans/{loanId}/assessments")
  public Response save(@PathParam("loanId") long id, String json) {
    return invoke(() -> assessments.save(id, json));
  }

  @GET
  @Path("/loans/{loanId}/assessments")
  public Response assessments(
      @PathParam("loanId") long id,
      @QueryParam("after") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> assessments.list(id, after, limit));
  }

  @GET
  @Path("/loans/{loanId}/assessments/{assessmentId}")
  public Response assessment(
      @PathParam("loanId") long id, @PathParam("assessmentId") String assessmentId) {
    return invoke(() -> assessments.get(id, assessmentId));
  }

  @GET
  @Path("/loans/{loanId}/accounting-policy")
  public Response policy(@PathParam("loanId") long id) {
    return invoke(() -> policies.get(id));
  }

  @GET
  @Path("/loans/{loanId}/accounting-policies")
  public Response policies(
      @PathParam("loanId") long id,
      @QueryParam("after") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> policies.list(id, after, limit));
  }

  @GET
  @Path("/loans/{loanId}/accounting-policies/{policyId}")
  public Response policyEvent(
      @PathParam("loanId") long id, @PathParam("policyId") String policyId) {
    return invoke(() -> policies.event(id, policyId));
  }

  @POST
  @Path("/loans/{loanId}/accounting-policies")
  public Response draftPolicy(@PathParam("loanId") long id, String json) {
    return invoke(() -> policies.draft(id, json));
  }

  @POST
  @Path("/loans/{loanId}/accounting-policies/{policyId}/approvals")
  public Response approvePolicy(
      @PathParam("loanId") long id, @PathParam("policyId") String policyId, String json) {
    return invoke(() -> policies.approve(id, policyId, json));
  }

  @GET
  @Path("/loans/{loanId}/reclassification-history")
  public Response reclassification(@PathParam("loanId") long id) {
    return invoke(() -> corrections.reclassification(id));
  }

  @GET
  @Path("/loans/{loanId}/writeoff-reconciliation")
  public Response reconciliation(
      @PathParam("loanId") long id, @QueryParam("assessmentId") String assessmentId) {
    return invoke(() -> corrections.get(id, assessmentId));
  }

  @POST
  @Path("/loans/{loanId}/reconciliation-assessments")
  public Response assessReconciliation(@PathParam("loanId") long id, String json) {
    return invoke(() -> corrections.assess(id, json));
  }

  @GET
  @Path("/loans/{loanId}/reconciliation-assessments")
  public Response reconciliationAssessments(
      @PathParam("loanId") long id,
      @QueryParam("after") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> corrections.list(id, "RECONCILIATION", after, limit));
  }

  @GET
  @Path("/loans/{loanId}/reconciliation-assessments/{recordId}")
  public Response reconciliationAssessment(
      @PathParam("loanId") long id, @PathParam("recordId") String recordId) {
    return invoke(() -> corrections.record(id, recordId, "RECONCILIATION"));
  }

  @POST
  @Path("/loans/{loanId}/adjustment-proposals")
  public Response propose(@PathParam("loanId") long id, String json) {
    return invoke(() -> corrections.propose(id, json));
  }

  @GET
  @Path("/loans/{loanId}/adjustments")
  public Response adjustments(
      @PathParam("loanId") long id,
      @QueryParam("after") @DefaultValue("0") long after,
      @QueryParam("limit") @DefaultValue("100") int limit) {
    return invoke(() -> corrections.list(id, "ADJUSTMENT_PROPOSAL", after, limit));
  }

  @GET
  @Path("/loans/{loanId}/adjustments/{recordId}")
  public Response adjustment(@PathParam("loanId") long id, @PathParam("recordId") String recordId) {
    return invoke(() -> corrections.record(id, recordId, "ADJUSTMENT_PROPOSAL"));
  }

  private static Response invoke(Supplier<?> call) {
    try {
      return Response.ok(AnpaSupport.JSON.toJson(call.get())).build();
    } catch (AnpaAssessmentAuditService.Conflict
        | DuplicateKeyException
        | ConcurrencyFailureException e) {
      return Response.status(409)
          .entity(
              AnpaSupport.JSON.toJson(
                  Map.of("reasonCode", "ASSESSMENT_CONFLICT", "financiallyExecutable", false)))
          .build();
    }
  }
}
