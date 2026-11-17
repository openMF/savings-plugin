/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentProductJson.JSON;
import static org.apache.fineract.investment.InvestmentProductValidation.invalid;
import static org.apache.fineract.investment.InvestmentRateData.*;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformResourceNotFoundException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Append-only tenant pricing revisions and independent, explicit metadata publication. */
@Service
@RequiredArgsConstructor
public class InvestmentRateService {
  public static final String READ = "READ_INVESTMENT_RATE";
  public static final String MANAGE = "MANAGE_INVESTMENT_RATE";
  public static final String PUBLISH = "APPROVE_INVESTMENT_RATE";
  private final PlatformSecurityContext security;
  private final JdbcTemplate jdbc;
  private final InvestmentProductAccess products;
  private final InvestmentProductValidation policies;
  private final InvestmentRateValidation validation;
  private final InvestmentRateTime time;

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_INVESTMENT_RATE')")
  @Transactional(readOnly = true)
  public Template template(long product) {
    authorize(READ);
    var n = products.product(product, false);
    var p = policy(product, false);
    String zone = time.timezone();
    return new Template(
        product,
        p.policy().investmentType(),
        n.currencyCode(),
        n.currencyDigits(),
        InvestmentRateValidation.MAX_BANDS,
        InvestmentRateValidation.MAX_BANDS,
        InvestmentRateValidation.MAX_CELLS,
        6,
        "LOWER_INCLUSIVE_UPPER_EXCLUSIVE; FINAL_PRODUCT_MAXIMUM_INCLUSIVE",
        "INCLUSIVE_POLICY_ELIGIBLE_DAYS",
        zone,
        Objects.toString(ThreadLocalContextUtil.getBusinessDate(), null),
        List.of(ValidityMode.values()),
        List.of(CampaignType.values()),
        READ,
        MANAGE,
        PUBLISH,
        capabilities(),
        time.blockers(new EffectiveValidity(ValidityMode.IMMEDIATE, null), zone, time.now()));
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','MANAGE_INVESTMENT_RATE')")
  @Transactional(readOnly = true)
  public ValidationResult validate(long product, String json) {
    authorize(MANAGE);
    var request = validation.parse(json);
    var n = products.product(product, false);
    var p = policy(product, false);
    checkRevision(product, request.expectedRevision());
    policies.validateAgainstNative(p.policy(), n);
    validation.validate(request.matrix(), p.policy(), n);
    var blockers = publicationBlockers(request, time.timezone(), time.now());
    return new ValidationResult(
        product, request.expectedRevision(), true, blockers.isEmpty(), request, blockers);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','MANAGE_INVESTMENT_RATE')")
  @Transactional(
      transactionManager = "jdbcTransactionManager",
      isolation = Isolation.READ_COMMITTED)
  public Configuration save(long product, String json) {
    long actor = authorize(MANAGE);
    var request = validation.parse(json);
    var n = products.product(product, true);
    var p = policy(product, true);
    var previous = latestDraft(product);
    long revision = previous == null ? 0 : previous.revision();
    if (revision != request.expectedRevision()) throw new Conflict("STALE_DRAFT_REVISION");
    policies.validateAgainstNative(p.policy(), n);
    validation.validate(request.matrix(), p.policy(), n);
    String zone = time.timezone();
    Instant now = time.now();
    var effective = applicable(product, now);
    jdbc.update(
        "INSERT INTO m_investment_rate_revision(product_id,revision,configuration_json,policy_revision,policy_json,native_json,maker_id,created_at_utc,timezone_id) VALUES (?,?,?,?,?,?,?,?,?)",
        product,
        revision + 1,
        JSON.toJson(request),
        p.revision(),
        JSON.toJson(p.policy()),
        JSON.toJson(n),
        actor,
        now.toString(),
        zone);
    audit(
        product,
        revision + 1,
        null,
        revision == 0 ? "CREATE_DRAFT" : "UPDATE_DRAFT",
        actor,
        now,
        effective == null ? null : effective.version(),
        null,
        previous == null ? null : previous.configuration(),
        request);
    return draftInternal(product);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_INVESTMENT_RATE')")
  @Transactional(readOnly = true)
  public Configuration draft(long product) {
    authorize(READ);
    products.product(product, false);
    return draftInternal(product);
  }

  /**
   * Checker approval is the explicit publication operation; edits alone never become selectable.
   */
  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','APPROVE_INVESTMENT_RATE')")
  @Transactional(
      transactionManager = "jdbcTransactionManager",
      isolation = Isolation.READ_COMMITTED)
  public Configuration publish(long product, String json) {
    long checker = authorize(PUBLISH);
    var request = validation.publishRequest(json);
    var n = products.product(product, true);
    var p = policy(product, true);
    var draft = latestDraft(product);
    if (draft == null) throw new NotFound();
    if (draft.revision() != request.expectedRevision()) throw new Conflict("STALE_DRAFT_REVISION");
    // READ_COMMITTED and the shared native-product lock give all writers a current view.
    var last = latestPublication(product);
    if (last != null && draft.revision() <= last.revision())
      throw new Conflict("DRAFT_ALREADY_PUBLISHED");
    long base = last == null ? 0 : last.revision();
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM m_investment_rate_revision WHERE product_id=? AND revision>? AND revision<=? AND maker_id=?",
            Long.class,
            product,
            base,
            draft.revision(),
            checker)
        > 0)
      throw new AccessDeniedException(
          "A rate draft contributor cannot approve their own proposal, including privileged users.");
    if (p.revision() != draft.policyRevision()
        || !p.policy().equals(draft.policySnapshot())
        || !n.equals(draft.nativeSnapshot())
        || !Objects.equals(time.timezone(), draft.timezone()))
      throw new Conflict("PRODUCT_POLICY_NATIVE_CONFIGURATION_OR_TIMEZONE_CHANGED");
    policies.validateAgainstNative(p.policy(), n);
    validation.validate(draft.configuration().matrix(), p.policy(), n);
    Instant now = time.now();
    var blockers = publicationBlockers(draft.configuration(), draft.timezone(), now);
    if (!blockers.isEmpty()) throw new PublicationBlocked(blockers);
    Instant boundary =
        InvestmentRateTime.boundary(
            draft.configuration().effectiveValidity(), draft.timezone(), now);
    // Append chronological schedules. Inserting before a previously reviewed future version is
    // unsafe.
    if (last != null && !boundary.isAfter(Instant.parse(last.effectiveInstant())))
      throw new Conflict("EFFECTIVE_BOUNDARY_MUST_FOLLOW_LAST_PUBLISHED_VERSION");
    String date = boundary.atZone(ZoneId.of(draft.timezone())).toLocalDate().toString();
    jdbc.update(
        "INSERT INTO m_investment_rate_publication(product_id,version,status,effective_micros,effective_date,timezone_id,checker_id,approved_at_utc,previous_version) VALUES (?,?,'APPROVED',?,?,?,?,?,?)",
        product,
        draft.revision(),
        InvestmentRateTime.micros(boundary),
        java.sql.Date.valueOf(date),
        draft.timezone(),
        checker,
        now.toString(),
        last == null ? null : last.version());
    audit(
        product,
        draft.revision(),
        draft.revision(),
        "PUBLISH",
        checker,
        now,
        last == null ? null : last.version(),
        boundary,
        last == null ? null : last.configuration(),
        draft.configuration());
    return published(product, draft.revision(), now);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_INVESTMENT_RATE')")
  @Transactional(readOnly = true)
  public Configuration current(long product) {
    authorize(READ);
    products.product(product, false);
    var result = applicable(product, time.now());
    if (result == null) throw new NotFound();
    return result;
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_INVESTMENT_RATE')")
  @Transactional(readOnly = true)
  public Configuration version(long product, long version) {
    authorize(READ);
    products.product(product, false);
    return published(product, version, time.now());
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_INVESTMENT_RATE')")
  @Transactional(readOnly = true)
  public List<Configuration> versions(long product, boolean scheduledOnly, int offset, int limit) {
    authorize(READ);
    page(offset, limit);
    products.product(product, false);
    Instant now = time.now();
    var versions =
        jdbc.queryForList(
            "SELECT version FROM m_investment_rate_publication WHERE product_id=?"
                + (scheduledOnly ? " AND effective_micros>?" : "")
                + " ORDER BY effective_micros DESC LIMIT ? OFFSET ?",
            Long.class,
            scheduledOnly
                ? new Object[] {product, InvestmentRateTime.micros(now), limit, offset}
                : new Object[] {product, limit, offset});
    return versions.stream().map(v -> published(product, v, now)).toList();
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_INVESTMENT_RATE')")
  @Transactional(readOnly = true)
  public Selection selection(
      long product, String amount, int term, String asOfInstant, String campaignId) {
    authorize(READ);
    var n = products.product(product, false);
    BigDecimal money;
    try {
      if (amount == null || !amount.matches("(0|[1-9][0-9]{0,12})(\\.[0-9]{1,12})?"))
        throw new NumberFormatException();
      money = new BigDecimal(amount);
    } catch (NumberFormatException e) {
      throw invalid("amount", "A nonnegative plain decimal is required.");
    }
    if (term <= 0) throw invalid("termDays", "A positive integer term is required.");
    if (campaignId != null)
      throw invalid(
          "campaignId",
          "Campaign selection is blocked: applicability and precedence are unapproved.");
    Instant at = asOfInstant == null ? time.now() : InvestmentRateTime.instant(asOfInstant);
    try {
      InvestmentRateTime.micros(at);
    } catch (ArithmeticException e) {
      throw invalid("asOfInstant", "Instant is outside supported storage range.");
    }
    var version = applicable(product, at);
    InvestmentRateValidation.money(money, version == null ? n : version.nativeSnapshot(), "amount");
    return InvestmentRateResolver.select(product, version, money, term, at);
  }

  @PreAuthorize("hasAnyAuthority('ALL_FUNCTIONS','READ_INVESTMENT_RATE')")
  @Transactional(readOnly = true)
  public List<Audit> history(long product, int offset, int limit) {
    authorize(READ);
    page(offset, limit);
    products.product(product, false);
    return jdbc.query(
        "SELECT event_json FROM m_investment_rate_audit WHERE product_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
        (rs, row) -> JSON.fromJson(rs.getString(1), Audit.class),
        product,
        limit,
        offset);
  }

  public void requireFinancialCapability() {
    throw invalid("investmentRate", "Native financial rate execution remains unavailable.");
  }

  private long authorize(String permission) {
    var actor = security.authenticatedUser();
    actor.validateHasPermissionTo(permission);
    if (ThreadLocalContextUtil.getTenant() == null)
      throw new AccessDeniedException("Authenticated tenant required.");
    return actor.getId();
  }

  private record PolicyRow(long revision, InvestmentProductData.Policy policy) {}

  private PolicyRow policy(long product, boolean lock) {
    var rows =
        jdbc.query(
            "SELECT revision,policy_json FROM m_investment_product_policy WHERE product_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (rs, row) ->
                new PolicyRow(
                    rs.getLong(1),
                    JSON.fromJson(rs.getString(2), InvestmentProductData.Policy.class)),
            product);
    if (rows.isEmpty()) throw new NotFound();
    return rows.getFirst();
  }

  private void checkRevision(long product, long expected) {
    var draft = latestDraft(product);
    if ((draft == null ? 0 : draft.revision()) != expected)
      throw new Conflict("STALE_DRAFT_REVISION");
  }

  private Configuration latestDraft(long product) {
    var rows =
        jdbc.query(
            "SELECT * FROM m_investment_rate_revision WHERE product_id=? ORDER BY revision DESC LIMIT 1",
            (rs, row) -> snapshot(rs),
            product);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private Configuration draftInternal(long product) {
    var result = latestDraft(product);
    if (result == null) throw new NotFound();
    return result;
  }

  private Configuration snapshot(ResultSet rs) throws SQLException {
    var p = JSON.fromJson(rs.getString("policy_json"), InvestmentProductData.Policy.class);
    var request = JSON.fromJson(rs.getString("configuration_json"), WriteRequest.class);
    return new Configuration(
        rs.getLong("product_id"),
        p.investmentType(),
        rs.getLong("revision"),
        null,
        Status.DRAFT,
        request.matrix().currencyCode(),
        request.effectiveValidity().effectiveDate(),
        null,
        rs.getString("timezone_id"),
        request,
        rs.getLong("policy_revision"),
        p,
        JSON.fromJson(rs.getString("native_json"), InvestmentProductData.NativeProduct.class),
        rs.getLong("maker_id"),
        rs.getString("created_at_utc"),
        null,
        null,
        null,
        capabilities(),
        publicationBlockers(request, rs.getString("timezone_id"), time.now()));
  }

  private Configuration latestPublication(long product) {
    var ids =
        jdbc.queryForList(
            "SELECT version FROM m_investment_rate_publication WHERE product_id=? ORDER BY effective_micros DESC LIMIT 1",
            Long.class,
            product);
    return ids.isEmpty() ? null : published(product, ids.getFirst(), time.now());
  }

  private Configuration applicable(long product, Instant at) {
    var ids =
        jdbc.queryForList(
            "SELECT version FROM m_investment_rate_publication WHERE product_id=? AND effective_micros<=? ORDER BY effective_micros DESC LIMIT 1",
            Long.class,
            product,
            InvestmentRateTime.micros(at));
    return ids.isEmpty() ? null : published(product, ids.getFirst(), time.now());
  }

  private Configuration published(long product, long version, Instant now) {
    var rows =
        jdbc.query(
            "SELECT r.*,p.effective_micros,p.effective_date,p.checker_id,p.approved_at_utc,p.previous_version FROM m_investment_rate_revision r JOIN m_investment_rate_publication p ON p.product_id=r.product_id AND p.version=r.revision WHERE r.product_id=? AND r.revision=?",
            (rs, row) -> {
              var d = snapshot(rs);
              Instant effective = InvestmentRateTime.fromMicros(rs.getLong("effective_micros"));
              boolean newerEffective =
                  jdbc.queryForObject(
                          "SELECT COUNT(*) FROM m_investment_rate_publication WHERE product_id=? AND effective_micros>? AND effective_micros<=?",
                          Long.class,
                          product,
                          rs.getLong("effective_micros"),
                          InvestmentRateTime.micros(now))
                      > 0;
              Status status =
                  effective.isAfter(now)
                      ? Status.APPROVED_SCHEDULED
                      : newerEffective ? Status.SUPERSEDED : Status.APPROVED_EFFECTIVE;
              Number previous = (Number) rs.getObject("previous_version");
              return new Configuration(
                  product,
                  d.investmentType(),
                  version,
                  version,
                  status,
                  d.currencyCode(),
                  rs.getString("effective_date"),
                  effective.toString(),
                  d.timezone(),
                  d.configuration(),
                  d.policyRevision(),
                  d.policySnapshot(),
                  d.nativeSnapshot(),
                  d.makerId(),
                  d.createdAtUtc(),
                  rs.getLong("checker_id"),
                  rs.getString("approved_at_utc"),
                  previous == null ? null : previous.longValue(),
                  capabilities(),
                  financialBlockers());
            },
            product,
            version);
    if (rows.isEmpty()) throw new NotFound();
    return rows.getFirst();
  }

  private List<String> publicationBlockers(WriteRequest request, String zone, Instant now) {
    var blockers =
        new ArrayList<>(InvestmentRateValidation.campaignBlockers(request.campaignMetadata()));
    blockers.addAll(time.blockers(request.effectiveValidity(), zone, now));
    return List.copyOf(blockers);
  }

  private void audit(
      long product,
      long revision,
      Long version,
      String action,
      long actor,
      Instant now,
      Long previousVersion,
      Instant effective,
      WriteRequest previous,
      WriteRequest next) {
    var event =
        new Audit(
            product,
            revision,
            version,
            action,
            actor,
            now.toString(),
            previousVersion,
            effective == null ? null : effective.toString(),
            previous,
            next,
            InvestmentRateResolver.changes(previous, next));
    jdbc.update(
        "INSERT INTO m_investment_rate_audit(product_id,revision,action,actor_id,recorded_at_utc,event_json) VALUES (?,?,?,?,?,?)",
        product,
        revision,
        action,
        actor,
        now.toString(),
        JSON.toJson(event));
  }

  private static void page(int offset, int limit) {
    if (offset < 0 || limit < 1 || limit > 200)
      throw invalid("pagination", "Offset must be nonnegative; limit must be 1–200.");
  }

  public static class Conflict extends RuntimeException {
    public Conflict(String code) {
      super(code);
    }
  }

  public static class PublicationBlocked extends RuntimeException {
    public final List<String> blockers;

    public PublicationBlocked(List<String> blockers) {
      super("Rate publication blocked.");
      this.blockers = List.copyOf(blockers);
    }
  }

  public static class NotFound extends AbstractPlatformResourceNotFoundException {
    public NotFound() {
      super(
          "error.msg.investment.rate.not.found",
          "Investment policy, rate draft or published version was not found in this tenant.");
    }
  }
}
