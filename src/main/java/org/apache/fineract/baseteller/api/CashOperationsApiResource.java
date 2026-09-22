package org.apache.fineract.baseteller.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.CashHoldingData;
import org.apache.fineract.baseteller.data.CashManagementStatus;
import org.apache.fineract.baseteller.data.CashOperationData;
import org.apache.fineract.baseteller.data.CashOperationRequest;
import org.apache.fineract.baseteller.data.CashOperationType;
import org.apache.fineract.baseteller.service.CashManagementReadPlatformService;
import org.apache.fineract.baseteller.service.CashManagementWritePlatformService;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/cash-operations")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Base Teller Cash Operations")
@RequiredArgsConstructor
public class CashOperationsApiResource {

  private static final Gson GSON =
      new GsonBuilder().registerTypeAdapter(LocalDate.class, new LocalDateAdapter()).create();

  private final PlatformSecurityContext context;
  private final CashManagementReadPlatformService readService;
  private final CashManagementWritePlatformService writeService;

  @POST
  @Operation(summary = "Create a deposit-in-transit or bank-deposit cash operation")
  public CashOperationData create(final String json) {
    context.authenticatedUser().validateHasPermissionTo("CREATE_CASH_DEPOSIT");
    return writeService.createOperation(GSON.fromJson(json, CashOperationRequest.class));
  }

  @GET
  @Operation(summary = "Search auditable cash-operation history")
  public Page<CashOperationData> history(
      @QueryParam("fromDate") final String fromDate,
      @QueryParam("toDate") final String toDate,
      @QueryParam("cashierId") final Long cashierId,
      @QueryParam("currencyCode") final String currencyCode,
      @QueryParam("status") final CashManagementStatus status,
      @QueryParam("transactionType") final CashOperationType transactionType,
      @QueryParam("q") final String query,
      @QueryParam("offset") final Integer offset,
      @QueryParam("limit") final Integer limit) {
    context.authenticatedUser().validateHasPermissionTo("READ_CASH_OPERATION_HISTORY");
    return readService.transactionHistory(
        CashierClosingsApiResource.parseDate(fromDate),
        CashierClosingsApiResource.parseDate(toDate),
        cashierId, currencyCode, status, transactionType, query, offset, limit);
  }

  @GET
  @Path("/holdings")
  @Operation(summary = "Retrieve authoritative cashier cash holdings")
  public List<CashHoldingData> holdings(
      @QueryParam("businessDate") final String businessDate,
      @QueryParam("cashierId") final Long cashierId,
      @QueryParam("currencyCode") final String currencyCode) {
    context.authenticatedUser().validateHasPermissionTo("READ_CASH_HOLDINGS");
    return readService.cashHoldings(CashierClosingsApiResource.parseDate(businessDate), cashierId, currencyCode);
  }

  private static final class LocalDateAdapter extends TypeAdapter<LocalDate> {
    @Override
    public void write(final JsonWriter out, final LocalDate value) throws IOException {
      if (value == null) out.nullValue(); else out.value(value.toString());
    }

    @Override
    public LocalDate read(final JsonReader in) throws IOException {
      return LocalDate.parse(in.nextString());
    }
  }
}
