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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.data.CashierClosingContextData;
import org.apache.fineract.baseteller.data.CashierClosingReceiptData;
import org.apache.fineract.baseteller.data.CashierClosingRequest;
import org.apache.fineract.baseteller.data.GlobalCashCountData;
import org.apache.fineract.baseteller.service.CashManagementReadPlatformService;
import org.apache.fineract.baseteller.service.CashManagementWritePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/closings")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Base Teller Cashier Closings")
@RequiredArgsConstructor
public class CashierClosingsApiResource {

  private static final Gson GSON =
      new GsonBuilder().registerTypeAdapter(LocalDate.class, new LocalDateAdapter()).create();

  private final PlatformSecurityContext context;
  private final CashManagementReadPlatformService readService;
  private final CashManagementWritePlatformService writeService;

  @GET
  @Path("/context")
  @Operation(summary = "Retrieve the authoritative closing position for a cashier")
  public CashierClosingContextData context(
      @QueryParam("cashierId") final Long cashierId,
      @QueryParam("currencyCode") final String currencyCode,
      @QueryParam("businessDate") final String businessDate) {
    context.authenticatedUser().validateHasPermissionTo("READ_CASHIER_CLOSING");
    return readService.closingContext(cashierId, currencyCode, parseDate(businessDate));
  }

  @POST
  @Operation(summary = "Finalize and authorize a cashier closing")
  public CashierClosingReceiptData close(final String json) {
    context.authenticatedUser().validateHasPermissionTo("CREATE_CASHIER_CLOSING");
    return writeService.close(GSON.fromJson(json, CashierClosingRequest.class));
  }

  @GET
  @Path("/{id}")
  @Operation(summary = "Retrieve cashier closing receipt data")
  public CashierClosingReceiptData closing(@PathParam("id") final Long id) {
    context.authenticatedUser().validateHasPermissionTo("READ_CASHIER_CLOSING");
    return readService.closing(id);
  }

  @GET
  @Path("/global")
  @Operation(summary = "Retrieve completed cashier closings for head-cashier settlement")
  public GlobalCashCountData global(
      @QueryParam("businessDate") final String businessDate,
      @QueryParam("currencyCode") final String currencyCode) {
    context.authenticatedUser().validateHasPermissionTo("READ_GLOBAL_SETTLEMENT");
    return readService.globalSettlementContext(parseDate(businessDate), currencyCode);
  }

  static LocalDate parseDate(final String value) {
    return value == null || value.isBlank() ? null : LocalDate.parse(value);
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
