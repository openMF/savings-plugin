package org.apache.fineract.baseteller.transfer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
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
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.Page;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/account-transfers")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Base Teller Account Transfers")
@RequiredArgsConstructor
public class BaseTellerAccountTransfersApiResource {
  private static final Gson GSON = new Gson();
  private final AccountTransferPlatformService service;

  @GET
  @Path("/context")
  public TransferData.Context context() {
    return service.context();
  }

  @GET
  @Path("/clients")
  public Page<TransferData.Client> clients(
      @QueryParam("query") String query,
      @QueryParam("clientNumber") String clientNumber,
      @QueryParam("name") String name,
      @QueryParam("offset") Integer offset,
      @QueryParam("limit") Integer limit) {
    return service.searchClients(query, clientNumber, name, offset, limit);
  }

  @GET
  @Path("/clients/{clientId}/accounts")
  public List<TransferData.Account> accounts(@PathParam("clientId") Long clientId) {
    return service.accounts(clientId);
  }

  @GET
  @Path("/clients/{clientId}/source-accounts")
  public List<TransferData.Account> sources(@PathParam("clientId") Long clientId) {
    return service.sources(clientId);
  }

  @GET
  @Path("/clients/{clientId}/destination-accounts")
  public List<TransferData.Account> destinations(
      @PathParam("clientId") Long clientId,
      @QueryParam("sourceAccountId") Long source,
      @QueryParam("operationType") TransferOperation operation,
      @QueryParam("beneficiaryClientId") Long beneficiary) {
    return service.destinations(clientId, source, operation, beneficiary);
  }

  @GET
  @Path("/accounts/lookup")
  public TransferData.Account lookup(
      @QueryParam("accountNumber") String number,
      @QueryParam("orderingClientId") Long client,
      @QueryParam("sourceAccountId") Long source,
      @QueryParam("operationType") TransferOperation operation) {
    return service.lookup(number, client, source, operation);
  }

  @POST
  @Path("/preview")
  public TransferData.Preview preview(String json) {
    return service.preview(parse(json));
  }

  @POST
  public TransferData.Receipt create(String json) {
    return service.create(parse(json));
  }

  @GET
  @Path("/{transferId}")
  public TransferData.Receipt retrieve(@PathParam("transferId") Long id) {
    return service.retrieve(id);
  }

  @GET
  @Path("/{transferId}/receipt")
  public TransferData.Receipt receipt(@PathParam("transferId") Long id) {
    return service.retrieve(id);
  }

  static TransferRequest parse(String json) {
    try {
      JsonObject object = JsonParser.parseString(json).getAsJsonObject();
      Set<String> fields =
          Set.of(
              "operationType",
              "orderingClientId",
              "sourceAccountId",
              "targetAccountType",
              "targetAccountId",
              "amount",
              "idempotencyKey");
      if (!fields.containsAll(object.keySet()))
        throw TransferValidation.invalid("json.fields", "Unsupported transfer request field.");
      if (!object.has("amount")
          || !object.get("amount").isJsonPrimitive()
          || !object.getAsJsonPrimitive("amount").isNumber()) {
        throw TransferValidation.invalid("amount", "Amount must be a JSON number.");
      }
      for (String id : Set.of("orderingClientId", "sourceAccountId", "targetAccountId")) {
        if (!object.has(id)
            || !object.get(id).isJsonPrimitive()
            || !object.getAsJsonPrimitive(id).isNumber()) {
          throw TransferValidation.invalid(
              "account.id", "Account and client IDs must be positive integers.");
        }
        object.get(id).getAsBigDecimal().longValueExact();
      }
      TransferRequest request = GSON.fromJson(object, TransferRequest.class);
      TransferValidation.request(request, false);
      return request;
    } catch (JsonParseException
        | IllegalStateException
        | NumberFormatException
        | ArithmeticException e) {
      throw TransferValidation.invalid("json", "Malformed transfer request or amount.");
    }
  }
}
