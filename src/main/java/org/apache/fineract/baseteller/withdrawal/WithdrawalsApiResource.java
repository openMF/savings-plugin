package org.apache.fineract.baseteller.withdrawal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.Page;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/withdrawals")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Base Teller Withdrawals")
@RequiredArgsConstructor
public class WithdrawalsApiResource {
  private final WithdrawalPlatformService service;

  @GET
  @Path("/context")
  public WithdrawalData.Context context() {
    return service.context();
  }

  @GET
  @Path("/customers")
  public Page<WithdrawalData.Customer> customers(
      @QueryParam("clientNumber") String number,
      @QueryParam("name") String name,
      @QueryParam("offset") Integer offset,
      @QueryParam("limit") Integer limit) {
    return service.customers(number, name, offset, limit);
  }

  @GET
  @Path("/customers/{clientId}/accounts")
  public List<WithdrawalData.Account> accounts(@PathParam("clientId") Long id) {
    return service.accounts(id);
  }

  @GET
  @Path("/accounts/{accountId}")
  public WithdrawalData.Account account(@PathParam("accountId") Long id) {
    return service.account(id);
  }

  @GET
  @Path("/banks")
  @Operation(summary = "Eligible outgoing issuer banks; empty while issuance is unsupported")
  public List<WithdrawalData.Option> banks() {
    return service.banks();
  }

  @GET
  @Path("/check-types")
  public List<WithdrawalData.Option> checkTypes() {
    return service.checkTypes();
  }

  @POST
  @Path("/preview")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Validate a cash withdrawal without posting; CHECK is unavailable")
  public WithdrawalData preview(String json) {
    return service.preview(WithdrawalValidation.parse(json, false));
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(
      summary = "Post one native savings withdrawal and exact physical cash outflow atomically")
  public WithdrawalData create(String json) {
    return service.create(WithdrawalValidation.parse(json, true));
  }

  @GET
  @Path("/{withdrawalId}")
  public WithdrawalData retrieve(@PathParam("withdrawalId") Long id) {
    return service.retrieve(id);
  }

  @GET
  @Path("/{withdrawalId}/receipt")
  public WithdrawalData receipt(@PathParam("withdrawalId") Long id) {
    return service.receipt(id);
  }

  @GET
  @Path("/{withdrawalId}/receipt")
  @Produces(MediaType.TEXT_HTML)
  public String printable(@PathParam("withdrawalId") Long id) {
    return WithdrawalReceipt.html(service.receipt(id));
  }
}
