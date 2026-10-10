package org.apache.fineract.baseteller.service;

import static org.apache.fineract.baseteller.service.EndOfDayClosingCalculator.invalid;

import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.Cashier;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

@Component
public class EndOfDayClosingAuthorization {
  private final AuthenticationProvider provider;
  private final JdbcTemplate jdbc;

  public EndOfDayClosingAuthorization(
      @Qualifier("customAuthenticationProvider") AuthenticationProvider provider,
      JdbcTemplate jdbc) {
    this.provider = provider;
    this.jdbc = jdbc;
  }

  /** Verify through Fineract's configured Spring provider without changing the caller principal. */
  public AppUser authorize(
      String username,
      String credential,
      AppUser actor,
      Cashier cashier,
      LocalDate date,
      List<String> currencies) {
    if (username == null || username.isBlank() || credential == null || credential.isBlank())
      throw invalid("authorization.required", "Primary Cashier authorization is required.");
    Authentication verified;
    var token = UsernamePasswordAuthenticationToken.unauthenticated(username, credential);
    try {
      verified = provider.authenticate(token);
    } catch (AuthenticationException e) {
      throw invalid("authorization.invalid", "Primary Cashier authorization failed.");
    } finally {
      token.eraseCredentials();
    }
    if (verified == null
        || !verified.isAuthenticated()
        || !(verified.getPrincipal() instanceof AppUser))
      throw invalid("authorization.invalid", "Primary Cashier authorization failed.");
    AppUser authorizer = (AppUser) verified.getPrincipal();
    // AppUser does not implement CredentialsContainer. Erase the returned token's presented
    // credential without modifying the managed user or its persisted password hash.
    if (verified instanceof org.springframework.security.core.CredentialsContainer container)
      container.eraseCredentials();
    if (!authorizer.hasAnyPermission("AUTHORIZE_BASE_TELLER_END_OF_DAY_CLOSING")
        || actor.getId().equals(authorizer.getId())
        || authorizer.getStaffId() == null
        || authorizer.getStaffId().equals(cashier.staffId())
        || authorizer.getOffice() == null)
      throw invalid("authorization.invalid", "Primary Cashier authorization failed.");
    Boolean office =
        jdbc.queryForObject(
            "SELECT COUNT(*)>0 FROM m_office WHERE id=? AND hierarchy LIKE ?",
            Boolean.class,
            cashier.officeId(),
            authorizer.getOffice().getHierarchy() + "%");
    if (!Boolean.TRUE.equals(office))
      throw invalid("authorization.invalid", "Primary Cashier authorization failed.");
    for (String currency : currencies) {
      Boolean primary =
          jdbc.queryForObject(
              """
              SELECT COUNT(*)>0 FROM m_cash_allocation a JOIN m_cashiers c ON c.id=a.destination_cashier_id
              JOIN m_tellers t ON t.id=c.teller_id
              WHERE a.operation_type='HEAD_CASHIER_ALLOCATION' AND a.status='COMPLETED'
              AND a.office_id=? AND a.business_date=? AND a.currency_code=? AND c.staff_id=?
              AND t.state=300 AND (c.start_date IS NULL OR c.start_date<=?)
              AND (c.end_date IS NULL OR c.end_date>=?)
              AND (t.valid_from IS NULL OR t.valid_from<=?) AND (t.valid_to IS NULL OR t.valid_to>=?)
              """,
              Boolean.class,
              cashier.officeId(),
              date,
              currency,
              authorizer.getStaffId(),
              date,
              date,
              date,
              date);
      if (!Boolean.TRUE.equals(primary))
        throw invalid("authorization.invalid", "Primary Cashier authorization failed.");
    }
    return authorizer;
  }
}
