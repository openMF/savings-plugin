package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.baseteller.data.EndOfDayClosingData.Cashier;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;

class EndOfDayClosingAuthorizationTest {
  private final AuthenticationProvider provider = mock(AuthenticationProvider.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final AppUser actor = mock(AppUser.class), authorizer = mock(AppUser.class);
  private final EndOfDayClosingAuthorization service =
      new EndOfDayClosingAuthorization(provider, jdbc);
  private final Cashier cashier = new Cashier(1L, 10L, "Cashier", 2L, "Teller", 3L, "Office");
  private final LocalDate date = LocalDate.of(2026, 1, 1);

  @BeforeEach
  void setup() {
    when(actor.getId()).thenReturn(1L);
    when(authorizer.getId()).thenReturn(2L);
    when(authorizer.getStaffId()).thenReturn(20L);
    when(authorizer.hasAnyPermission("AUTHORIZE_BASE_TELLER_END_OF_DAY_CLOSING")).thenReturn(true);
    Office office = mock(Office.class);
    when(office.getHierarchy()).thenReturn(".1.");
    when(authorizer.getOffice()).thenReturn(office);
    when(provider.authenticate(any()))
        .thenReturn(UsernamePasswordAuthenticationToken.authenticated(authorizer, null, List.of()));
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
  }

  private AppUser authorize() {
    return service.authorize(
        "primary", "test-only-credential", actor, cashier, date, List.of("AAA"));
  }

  @Test
  void validPrimaryCashier() {
    assertSame(authorizer, authorize());
  }

  @Test
  void invalidCredentialHasSanitizedExceptionAndErasesRequestToken() {
    when(provider.authenticate(any()))
        .thenAnswer(
            call -> {
              throw new BadCredentialsException("test-only-credential");
            });
    RuntimeException ex = assertThrows(RuntimeException.class, this::authorize);
    assertFalse(ex.toString().contains("test-only-credential"));
    assertNull(ex.getCause());
    var captor = org.mockito.ArgumentCaptor.forClass(Authentication.class);
    verify(provider).authenticate(captor.capture());
    assertNull(captor.getValue().getCredentials());
  }

  @Test
  void userWithoutAuthorizationPermissionIsRejected() {
    when(authorizer.hasAnyPermission("AUTHORIZE_BASE_TELLER_END_OF_DAY_CLOSING")).thenReturn(false);
    assertThrows(RuntimeException.class, this::authorize);
  }

  @Test
  void arbitraryValidUserWithoutPrimaryCashierAssignmentIsRejected() {
    when(authorizer.getStaffId()).thenReturn(null);
    assertThrows(RuntimeException.class, this::authorize);
  }

  @Test
  void selfAuthorizationIsRejected() {
    when(authorizer.getId()).thenReturn(1L);
    assertThrows(RuntimeException.class, this::authorize);
  }

  @Test
  void anotherOfficeOrUnfundedHeadCashierIsRejected() {
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
        .thenReturn(false);
    assertThrows(RuntimeException.class, this::authorize);
  }

  @Test
  void fundedHeadCashierRelationshipIsRequiredAfterOfficeValidation() {
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
        .thenReturn(true, false);
    assertThrows(RuntimeException.class, this::authorize);
  }

  @Test
  void targetCashierCannotAuthorizeThroughAnotherUserIdentity() {
    when(authorizer.getStaffId()).thenReturn(10L);
    assertThrows(RuntimeException.class, this::authorize);
  }

  @Test
  void missingCredentialIsRejectedWithoutCallingProvider() {
    assertThrows(
        RuntimeException.class,
        () -> service.authorize("primary", null, actor, cashier, date, List.of("AAA")));
    verifyNoInteractions(provider);
  }
}
