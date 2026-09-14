package org.apache.fineract.baseteller.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.baseteller.data.BaseTellerCheckClearingStatus;
import org.apache.fineract.baseteller.data.BaseTellerDepositCheckRequest;
import org.apache.fineract.baseteller.data.BaseTellerDepositFundingData;
import org.apache.fineract.baseteller.data.BaseTellerDepositRequest;
import org.apache.fineract.baseteller.data.BaseTellerFundingType;
import org.apache.fineract.baseteller.validation.BaseTellerDepositValidator;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.teller.service.TellerManagementReadPlatformService;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadService;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class BaseTellerWritePlatformServiceImplTest {

  @Test
  void clearedCheckDepositRequiresDedicatedAuthorizationPermission() {
    final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    final NamedParameterJdbcTemplate namedParameterJdbcTemplate =
        mock(NamedParameterJdbcTemplate.class);
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    final AppUser user = mock(AppUser.class);
    final BaseTellerReadPlatformService readPlatformService =
        mock(BaseTellerReadPlatformService.class);
    final ClientRepositoryWrapper clientRepository = mock(ClientRepositoryWrapper.class);
    final SavingsAccountReadPlatformService savingsAccountReadPlatformService =
        mock(SavingsAccountReadPlatformService.class);
    final PaymentTypeReadService paymentTypeReadService = mock(PaymentTypeReadService.class);
    final TellerManagementReadPlatformService tellerManagementReadPlatformService =
        mock(TellerManagementReadPlatformService.class);
    final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService =
        mock(PortfolioCommandSourceWritePlatformService.class);

    when(context.authenticatedUser()).thenReturn(user);
    when(namedParameterJdbcTemplate.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenReturn(List.of());
    doThrow(new GeneralPlatformDomainRuleException("permission.denied", "Denied"))
        .when(user)
        .validateHasPermissionTo("AUTHORIZE_BASE_TELLER_CHECK_CLEARING");

    final BaseTellerWritePlatformServiceImpl service =
        new BaseTellerWritePlatformServiceImpl(
            jdbcTemplate,
            namedParameterJdbcTemplate,
            context,
            new BaseTellerDepositValidator(),
            readPlatformService,
            clientRepository,
            savingsAccountReadPlatformService,
            paymentTypeReadService,
            tellerManagementReadPlatformService,
            commandsSourceWritePlatformService);

    final BaseTellerDepositRequest request =
        new BaseTellerDepositRequest(
            "idem-1",
            11L,
            33L,
            "en",
            "yyyy-MM-dd",
            "2026-09-13",
            new BaseTellerDepositFundingData(
                BaseTellerFundingType.CHECK,
                new BigDecimal("50.00"),
                "USD",
                2L,
                null,
                List.of(
                    new BaseTellerDepositCheckRequest(
                        "PERSONAL",
                        "ABC",
                        "123",
                        new BigDecimal("50.00"),
                        BaseTellerCheckClearingStatus.CLEARED,
                        null,
                        null))));

    assertThrows(GeneralPlatformDomainRuleException.class, () -> service.deposit(request));
  }
}
