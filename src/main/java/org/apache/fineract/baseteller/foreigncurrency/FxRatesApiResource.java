package org.apache.fineract.baseteller.foreigncurrency;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@Path("/v2/foreign-currency/rates")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Foreign Currency Rate Drafts",
    description = "Non-operational branch configuration. Publication is disabled.")
@RequiredArgsConstructor
public class FxRatesApiResource {
  private final FxRateService service;

  @GET
  @Path("/context")
  public FxData.RateContext context() {
    return service.context();
  }

  @GET
  public List<FxData.RateDraft> history(
      @QueryParam("officeId") Long office,
      @QueryParam("effectiveDate") String date,
      @QueryParam("offset") Integer offset,
      @QueryParam("limit") Integer limit) {
    return service.history(office, date, offset, limit);
  }

  @GET
  @Path("/{id}")
  public FxData.RateDraft retrieve(@PathParam("id") Long id) {
    return service.retrieve(id);
  }

  @GET
  @Path("/{id}/audit")
  public List<FxData.RateAudit> audit(@PathParam("id") Long id) {
    return service.audit(id);
  }

  @POST
  @Path("/preview")
  public FxData.RatePreview preview(String body) {
    return service.preview(FxValidation.rate(body));
  }

  @POST
  @Operation(summary = "Confirm immutable branch drafts and audit records; does not activate rates")
  public List<FxData.RateDraft> save(String body) {
    return service.save(FxValidation.rate(body));
  }

  @POST
  @Path("/publish")
  @Operation(summary = "Always rejects publication until institutional policies are implemented")
  public void publish(String body) {
    service.publish();
  }
}
