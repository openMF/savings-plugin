package org.apache.fineract.baseteller.foreigncurrency;

import static org.apache.fineract.baseteller.foreigncurrency.FxValidation.*;

import com.google.gson.Gson;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.baseteller.cashtransfer.CashTransferValidation;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Immutable branch drafts, isolated from all executable exchange-rate resolution. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, transactionManager = "jdbcTransactionManager")
public class FxRateService {
  private static final Gson GSON = new Gson();
  private static final String SELECT =
      "SELECT d.*,o.name office_name FROM m_fx_rate_draft d JOIN m_office o ON o.id=d.office_id";
  private final PlatformSecurityContext security;
  private final JdbcTemplate jdbc;

  @PreAuthorize("hasAuthority('ALL_FUNCTIONS') or hasAuthority('READ_BASE_TELLER_FX_RATE')")
  public FxData.RateContext context() {
    AppUser actor = user("READ");
    return new FxData.RateContext(
        actor.getId(),
        DateUtils.getBusinessLocalDate().toString(),
        jdbc.query(
            "SELECT id,name FROM m_office WHERE hierarchy LIKE ? ORDER BY id",
            (rs, i) -> new FxData.Branch(rs.getLong(1), rs.getString(2)),
            hierarchy(actor)),
        currencies(),
        null,
        "UNCONFIRMED",
        "UNINTERPRETED_DRAFT_VALUES",
        capabilities());
  }

  @PreAuthorize("hasAuthority('ALL_FUNCTIONS') or hasAuthority('READ_BASE_TELLER_FX_RATE')")
  public List<FxData.RateDraft> history(
      Long officeId, String effectiveDate, Integer offset, Integer limit) {
    AppUser actor = user("READ");
    return history(actor, officeId, effectiveDate, offset, limit);
  }

  private List<FxData.RateDraft> history(
      AppUser actor, Long officeId, String effectiveDate, Integer offset, Integer limit) {
    int start = offset == null ? 0 : offset, size = limit == null ? 100 : limit;
    if (start < 0 || size < 1 || size > 100)
      throw invalid("page.invalid", "Use offset >= 0 and limit 1-100.");
    String sql = SELECT + " WHERE o.hierarchy LIKE ?";
    List<Object> params = new ArrayList<>(List.of(hierarchy(actor)));
    if (officeId != null) {
      branch(officeId, actor);
      sql += " AND d.office_id=?";
      params.add(officeId);
    }
    if (effectiveDate != null) {
      sql += " AND d.effective_date=?";
      params.add(date(effectiveDate));
    }
    sql += " ORDER BY d.effective_date DESC,d.id DESC LIMIT ? OFFSET ?";
    params.add(size);
    params.add(start);
    return jdbc.query(sql, this::map, params.toArray());
  }

  @PreAuthorize("hasAuthority('ALL_FUNCTIONS') or hasAuthority('READ_BASE_TELLER_FX_RATE')")
  public FxData.RateDraft retrieve(Long id) {
    return draft(id, user("READ"));
  }

  FxData.RateDraft draft(Long id, AppUser actor) {
    var rows =
        jdbc.query(
            SELECT + " WHERE d.id=? AND o.hierarchy LIKE ?", this::map, id, hierarchy(actor));
    if (rows.size() != 1)
      throw invalid("rate.not.found", "Rate draft not found in the authenticated office scope.");
    return rows.getFirst();
  }

  List<FxData.RateDraft> currentDrafts(Long office, LocalDate date, AppUser actor) {
    branch(office, actor);
    return jdbc.query(
        SELECT
            + " WHERE d.office_id=? AND d.effective_date=? AND NOT EXISTS (SELECT 1 FROM"
            + " m_fx_rate_draft n WHERE n.office_id=d.office_id AND"
            + " n.effective_date=d.effective_date AND n.base_currency=d.base_currency AND"
            + " n.quote_currency=d.quote_currency AND n.version>d.version) ORDER BY"
            + " d.quote_currency",
        this::map,
        office,
        date);
  }

  @PreAuthorize("hasAuthority('ALL_FUNCTIONS') or hasAuthority('CREATE_BASE_TELLER_FX_RATE')")
  public FxData.RatePreview preview(FxData.RateRequest request) {
    return prepare(request, user("CREATE"));
  }

  @Transactional(
      transactionManager = "jdbcTransactionManager",
      isolation = Isolation.READ_COMMITTED)
  @PreAuthorize("hasAuthority('ALL_FUNCTIONS') or hasAuthority('CREATE_BASE_TELLER_FX_RATE')")
  public List<FxData.RateDraft> save(FxData.RateRequest request) {
    AppUser actor = user("CREATE");
    FxValidation.validate(request);
    // Serialize revisions across every targeted branch, including first creation. Fixed order
    // prevents opposing bulk requests from acquiring branch locks in different orders.
    for (var b : sorted(request)) {
      var locked =
          jdbc.queryForList(
              "SELECT id FROM m_office WHERE id=? AND hierarchy LIKE ? FOR UPDATE",
              Long.class,
              b.officeId(),
              hierarchy(actor));
      if (locked.size() != 1)
        throw invalid("branch.forbidden", "Branch is outside the authenticated office scope.");
    }
    var preview = prepare(request, actor);
    if (request.previewToken() == null || !request.previewToken().equals(preview.previewToken()))
      throw invalid(
          "preview.stale", "Preview these exact changes again before confirming the draft.");
    List<FxData.RateDraft> result = new ArrayList<>();
    for (var change : preview.changes()) {
      var b = change.proposed();
      long version = b.expectedVersion() + 1;
      var key = new GeneratedKeyHolder();
      jdbc.update(
          c -> {
            var s =
                c.prepareStatement(
                    "INSERT INTO m_fx_rate_draft"
                        + " (office_id,effective_date,base_currency,quote_currency,"
                        + "daily_base_rate,buy_points,sell_points,version,status,created_by,created_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,'DRAFT',?,CURRENT_TIMESTAMP)",
                    new String[] {"id"});
            s.setLong(1, b.officeId());
            s.setObject(2, date(request.effectiveDate()));
            s.setString(3, request.baseCurrency());
            s.setString(4, request.quoteCurrency());
            s.setBigDecimal(5, request.dailyBaseRate());
            s.setBigDecimal(6, b.buyPoints());
            s.setBigDecimal(7, b.sellPoints());
            s.setLong(8, version);
            s.setLong(9, actor.getId());
            return s;
          },
          key);
      Long id = key.getKey().longValue();
      jdbc.update(
          "INSERT INTO m_fx_rate_audit (draft_id,previous_draft_id,operator_id,action,recorded_at)"
              + " VALUES(?,?,?,'DRAFT_CONFIRMED',CURRENT_TIMESTAMP)",
          id,
          change.previous() == null ? null : change.previous().id(),
          actor.getId());
      result.add(draft(id, actor));
    }
    return List.copyOf(result);
  }

  @PreAuthorize("hasAuthority('ALL_FUNCTIONS') or hasAuthority('READ_BASE_TELLER_FX_RATE')")
  public List<FxData.RateAudit> audit(Long id) {
    AppUser actor = user("READ");
    var selected = draft(id, actor);
    return jdbc.query(
        "SELECT a.id,a.draft_id,a.previous_draft_id,a.operator_id,a.action,a.recorded_at"
            + " FROM m_fx_rate_audit a JOIN m_fx_rate_draft d ON d.id=a.draft_id WHERE"
            + " d.office_id=? AND d.effective_date=? AND d.base_currency=? AND d.quote_currency=?"
            + " AND d.version<=? ORDER BY d.version",
        (rs, i) -> {
          Long previous = rs.getObject(3, Long.class);
          return new FxData.RateAudit(
              rs.getLong(1),
              rs.getLong(2),
              previous,
              rs.getLong(4),
              rs.getString(5),
              rs.getTimestamp(6).toInstant().toString(),
              previous == null ? null : draft(previous, actor),
              draft(rs.getLong(2), actor));
        },
        selected.officeId(),
        date(selected.effectiveDate()),
        selected.baseCurrency(),
        selected.quoteCurrency(),
        selected.version());
  }

  @PreAuthorize("hasAuthority('ALL_FUNCTIONS') or hasAuthority('PUBLISH_BASE_TELLER_FX_RATE')")
  public void publish() {
    user("PUBLISH");
    blocked("FX rate publication");
  }

  private FxData.RatePreview prepare(FxData.RateRequest request, AppUser actor) {
    FxValidation.validate(request);
    currency(request.baseCurrency());
    currency(request.quoteCurrency());
    List<FxData.RateChange> changes = new ArrayList<>();
    for (var b : sorted(request)) {
      var office = branch(b.officeId(), actor);
      var previous = latest(b.officeId(), date(request.effectiveDate()), request.quoteCurrency());
      if ((previous == null ? 0L : previous.version()) != b.expectedVersion())
        throw invalid(
            "version.conflict",
            "A targeted branch draft has changed. Retrieve its latest version and preview again.");
      changes.add(
          new FxData.RateChange(
              office,
              previous,
              new FxData.BranchInput(
                  b.officeId(),
                  b.expectedVersion(),
                  canonical(b.buyPoints()),
                  canonical(b.sellPoints()))));
    }
    String token =
        CashTransferValidation.hash(
            actor.getId()
                + ":"
                + request.effectiveDate()
                + ":"
                + request.baseCurrency()
                + ":"
                + request.quoteCurrency()
                + ":"
                + canonical(request.dailyBaseRate()).toPlainString()
                + ":"
                + GSON.toJson(changes));
    return new FxData.RatePreview(
        actor.getId(),
        request.effectiveDate(),
        request.baseCurrency(),
        request.quoteCurrency(),
        request.dailyBaseRate(),
        List.copyOf(changes),
        token,
        "DRAFT_PREVIEW",
        capabilities());
  }

  FxData.RateDraft latest(Long office, LocalDate date, String quote) {
    var rows =
        jdbc.query(
            SELECT
                + " WHERE d.office_id=? AND d.effective_date=? AND d.base_currency='USD'"
                + " AND d.quote_currency=? ORDER BY d.version DESC LIMIT 1",
            this::map,
            office,
            date,
            quote);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  List<FxData.Currency> currencies() {
    return jdbc.query(
        "SELECT code,name,decimal_places FROM m_organisation_currency ORDER BY code",
        (rs, i) -> new FxData.Currency(rs.getString(1), rs.getString(2), rs.getInt(3)));
  }

  FxData.Currency currency(String code) {
    return currencies().stream()
        .filter(c -> c.code().equals(code))
        .findFirst()
        .orElseThrow(
            () -> invalid("currency.disabled", "Currency is not enabled for this tenant."));
  }

  private FxData.Branch branch(Long id, AppUser actor) {
    var rows =
        jdbc.query(
            "SELECT id,name FROM m_office WHERE id=? AND hierarchy LIKE ?",
            (rs, i) -> new FxData.Branch(rs.getLong(1), rs.getString(2)),
            id,
            hierarchy(actor));
    if (rows.size() != 1)
      throw invalid("branch.forbidden", "Branch is outside the authenticated office scope.");
    return rows.getFirst();
  }

  private AppUser user(String action) {
    AppUser actor = security.authenticatedUser();
    actor.validateHasPermissionTo(action + "_" + RATES);
    hierarchy(actor);
    return actor;
  }

  static String hierarchy(AppUser actor) {
    if (actor.getOffice() == null || actor.getOffice().getHierarchy() == null)
      throw invalid("office.required", "An authenticated office hierarchy is required.");
    return actor.getOffice().getHierarchy() + "%";
  }

  private static List<FxData.BranchInput> sorted(FxData.RateRequest r) {
    return r.branches().stream()
        .sorted(Comparator.comparing(FxData.BranchInput::officeId))
        .toList();
  }

  private static BigDecimal canonical(BigDecimal n) {
    return n.stripTrailingZeros();
  }

  private FxData.RateDraft map(ResultSet rs, int row) throws SQLException {
    return new FxData.RateDraft(
        rs.getLong("id"),
        rs.getLong("office_id"),
        rs.getString("office_name"),
        rs.getDate("effective_date").toLocalDate().toString(),
        rs.getString("base_currency"),
        rs.getString("quote_currency"),
        rs.getBigDecimal("daily_base_rate"),
        rs.getBigDecimal("buy_points"),
        rs.getBigDecimal("sell_points"),
        rs.getLong("version"),
        rs.getString("status"),
        rs.getLong("created_by"),
        rs.getTimestamp("created_at").toInstant().toString());
  }
}
