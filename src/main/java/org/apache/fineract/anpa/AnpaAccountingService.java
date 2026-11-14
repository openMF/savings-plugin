/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;

import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One consistent database view per diagnostic; no GET persists evidence or modifies native data.
 */
@Service
@RequiredArgsConstructor
@Transactional(
    transactionManager = "jdbcTransactionManager",
    readOnly = true,
    isolation = Isolation.REPEATABLE_READ)
public class AnpaAccountingService {
  private final AnpaAccountingAccess access;
  private final AnpaAccountingEvidenceService evidence;
  private final AnpaWriteOffPreviewService engine;
  private final AnpaHistoryService history;

  public Template template() {
    access.authorize(AnpaAccountingAccess.READ);
    return new Template(
        "0.0.1628-bc1c598",
        new Capabilities(true, false, true, false, true, true, true, false, false),
        Arrays.asList(Category.values()),
        Arrays.asList(Reason.values()),
        "Current flag only; native NPA transition history and NPA receivable segregation unavailable.");
  }

  public Eligibility eligibility(long loanId) {
    access.authorize(AnpaAccountingAccess.READ);
    var loan = evidence.loan(loanId);
    var sources = evidence.collect(loan, loan.businessDate());
    return engine.eligibility(
        sources, engine.calculate(access.tenant(), sources, loan.businessDate()).blockers());
  }

  public History history(long loanId) {
    access.authorize(AnpaAccountingAccess.READ);
    var loan = evidence.loan(loanId);
    return history.resolve(loan, loan.businessDate());
  }

  public Preview preview(long loanId, String asOf) {
    access.authorize(AnpaAccountingAccess.PREVIEW);
    return calculate(loanId, asOf);
  }

  public List<AccountBucket> receivables(long loanId) {
    access.authorize(AnpaAccountingAccess.READ);
    return calculate(loanId, null).receivables();
  }

  Preview calculate(long loanId, String date) {
    var day = AnpaSupport.date(date, DateUtils.getBusinessLocalDate());
    var loan = evidence.loan(loanId);
    return engine.calculate(access.tenant(), evidence.collect(loan, day), day);
  }
}
