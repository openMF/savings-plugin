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
import org.apache.fineract.baseteller.data.CashAllocationContextData;
import org.apache.fineract.baseteller.data.CashAllocationPreviewData;
import org.apache.fineract.baseteller.data.CashAllocationReceiptData;
import org.apache.fineract.baseteller.data.CashAllocationRequest;
import org.apache.fineract.baseteller.service.CashAllocationReadPlatformService;
import org.apache.fineract.baseteller.service.CashAllocationWritePlatformService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/cash-allocations")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Base Teller Cash Allocations")
@RequiredArgsConstructor
public class CashAllocationsApiResource {

  private static final String RESOURCE = "BASE_TELLER_CASH_ALLOCATION";
  private static final Gson GSON =
      new GsonBuilder().registerTypeAdapter(LocalDate.class, new LocalDateAdapter()).create();

  private final PlatformSecurityContext context;
  private final CashAllocationReadPlatformService readService;
  private final CashAllocationWritePlatformService writeService;

  @GET
  @Path("/context")
  @Operation(summary = "Retrieve authoritative cash-opening context")
  public CashAllocationContextData allocationContext(
      @QueryParam("officeId") final Long officeId,
      @QueryParam("currencyCode") final String currencyCode) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readService.context(officeId, currencyCode);
  }

  @POST
  @Path("/preview")
  @Operation(summary = "Calculate an authoritative cash-allocation preview")
  public CashAllocationPreviewData preview(final String json) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readService.preview(GSON.fromJson(json, CashAllocationRequest.class));
  }

  @POST
  @Operation(summary = "Create a safe, head-cashier, or operational-teller allocation")
  public CashAllocationReceiptData allocate(final String json) {
    context.authenticatedUser().validateHasCreatePermission(RESOURCE);
    return writeService.allocate(GSON.fromJson(json, CashAllocationRequest.class));
  }

  @GET
  @Path("/{allocationId}")
  @Operation(summary = "Retrieve a completed cash allocation")
  public CashAllocationReceiptData retrieve(@PathParam("allocationId") final Long allocationId) {
    context.authenticatedUser().validateHasReadPermission(RESOURCE);
    return readService.retrieve(allocationId);
  }

  @GET
  @Path("/{allocationId}/receipt")
  @Operation(summary = "Reprint the immutable cash-allocation receipt")
  public CashAllocationReceiptData reprint(@PathParam("allocationId") final Long allocationId) {
    context.authenticatedUser().validateHasPermissionTo("REPRINT_BASE_TELLER_CASH_ALLOCATION");
    return readService.reprint(allocationId);
  }

  private static final class LocalDateAdapter extends TypeAdapter<LocalDate> {
    @Override
    public void write(final JsonWriter out, final LocalDate value) throws IOException {
      if (value == null) out.nullValue();
      else out.value(value.toString());
    }

    @Override
    public LocalDate read(final JsonReader in) throws IOException {
      return LocalDate.parse(in.nextString());
    }
  }
}
