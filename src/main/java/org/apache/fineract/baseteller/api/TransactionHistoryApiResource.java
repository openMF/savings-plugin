package org.apache.fineract.baseteller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.TransactionHistoryContextData;
import org.apache.fineract.baseteller.data.TransactionHistoryDenominationsData;
import org.apache.fineract.baseteller.data.TransactionHistoryDetailData;
import org.apache.fineract.baseteller.data.TransactionHistoryQuery;
import org.apache.fineract.baseteller.data.TransactionHistoryReceiptData;
import org.apache.fineract.baseteller.data.TransactionHistoryReportData;
import org.apache.fineract.baseteller.data.TransactionHistorySearchData;
import org.apache.fineract.baseteller.service.TransactionHistoryReadPlatformService;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.stereotype.Component;

@Path("/v2/base-teller/transaction-history")
@Component
@Produces(MediaType.APPLICATION_JSON)
@Tag(
    name = "Base Teller Transaction History",
    description =
        "Authorized, source-backed history of Base Teller transactions. Requires READ_BASE_TELLER_TRANSACTION_HISTORY.")
@RequiredArgsConstructor
public class TransactionHistoryApiResource {

  private static final String PERMISSION = "READ_BASE_TELLER_TRANSACTION_HISTORY";

  private final PlatformSecurityContext securityContext;
  private final TransactionHistoryReadPlatformService readPlatformService;

  @GET
  @Path("/context")
  @Operation(summary = "Retrieve authorized transaction-history filter values")
  public TransactionHistoryContextData context() {
    authorize();
    return readPlatformService.context();
  }

  @GET
  @Operation(
      summary = "Search Base Teller transaction history",
      description =
          "Returns one canonical row per logical transaction, database-side pagination, and totals over the complete filtered result.")
  public TransactionHistorySearchData search(
      @QueryParam("fromDate") final String fromDate,
      @QueryParam("toDate") final String toDate,
      @Parameter(description = "Cashier identifier returned by context") @QueryParam("tellerId")
          final Long tellerId,
      @QueryParam("currencyCode") final String currencyCode,
      @QueryParam("status") final String status,
      @QueryParam("type") final String type,
      @QueryParam("operation") final String operation,
      @QueryParam("concept") final String concept,
      @QueryParam("reference") final String reference,
      @QueryParam("offset") final Integer offset,
      @QueryParam("limit") final Integer limit,
      @QueryParam("sort") final String sort,
      @QueryParam("order") final String order) {
    authorize();
    return readPlatformService.search(
        query(
            fromDate,
            toDate,
            tellerId,
            currencyCode,
            status,
            type,
            operation,
            concept,
            reference,
            offset,
            limit,
            sort,
            order));
  }

  @GET
  @Path("/report")
  @Operation(
      summary = "Retrieve the complete filtered history report dataset",
      description =
          "Returns all matching rows in stable order plus per-currency totals for Web App printing.")
  public TransactionHistoryReportData report(
      @QueryParam("fromDate") final String fromDate,
      @QueryParam("toDate") final String toDate,
      @QueryParam("tellerId") final Long tellerId,
      @QueryParam("currencyCode") final String currencyCode,
      @QueryParam("status") final String status,
      @QueryParam("type") final String type,
      @QueryParam("operation") final String operation,
      @QueryParam("concept") final String concept,
      @QueryParam("reference") final String reference,
      @QueryParam("sort") final String sort,
      @QueryParam("order") final String order) {
    authorize();
    return readPlatformService.report(
        query(
            fromDate,
            toDate,
            tellerId,
            currencyCode,
            status,
            type,
            operation,
            concept,
            reference,
            null,
            null,
            sort,
            order));
  }

  @GET
  @Path("/{historyId}")
  @Operation(summary = "Retrieve source-authoritative transaction detail")
  public TransactionHistoryDetailData detail(@PathParam("historyId") final String historyId) {
    authorize();
    return readPlatformService.detail(historyId);
  }

  @GET
  @Path("/{historyId}/denominations")
  @Operation(
      summary = "Retrieve exact persisted denomination detail",
      description = "Unsupported or uncaptured breakdowns are returned explicitly and never inferred.")
  public TransactionHistoryDenominationsData denominations(
      @PathParam("historyId") final String historyId) {
    authorize();
    return readPlatformService.denominations(historyId);
  }

  @GET
  @Path("/{historyId}/receipt")
  @Operation(
      summary = "Retrieve or reprint the original transaction receipt",
      description = "Delegates to the authoritative source receipt service without financial side effects.")
  public TransactionHistoryReceiptData receipt(@PathParam("historyId") final String historyId) {
    authorize();
    return readPlatformService.receipt(historyId);
  }

  private void authorize() {
    securityContext.authenticatedUser().validateHasPermissionTo(PERMISSION);
  }

  private static TransactionHistoryQuery query(
      final String fromDate,
      final String toDate,
      final Long tellerId,
      final String currencyCode,
      final String status,
      final String type,
      final String operation,
      final String concept,
      final String reference,
      final Integer offset,
      final Integer limit,
      final String sort,
      final String order) {
    return new TransactionHistoryQuery(
        date(fromDate, "fromDate"),
        date(toDate, "toDate"),
        tellerId,
        currencyCode,
        status,
        type,
        operation,
        concept,
        reference,
        offset,
        limit,
        sort,
        order);
  }

  private static LocalDate date(final String value, final String field) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException exception) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.transaction.history.date.invalid",
          field + " must use ISO yyyy-MM-dd format.");
    }
  }
}
