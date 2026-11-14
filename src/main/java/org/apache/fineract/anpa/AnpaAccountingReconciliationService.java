/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reconciliation differences are reported; never allocated to regular income to force a balance.
 */
@Component
public class AnpaAccountingReconciliationService {
  List<Reconciliation> reconcile(Loan loan, LocalDate asOf, List<AccountBucket> buckets) {
    return java.util.Arrays.stream(Category.values())
        .map(
            category -> {
              BigDecimal nativeAmount =
                  switch (category) {
                    case INTEREST -> loan.currentOutstanding().interest();
                    case FEE_COMMISSION -> loan.currentOutstanding().fees();
                    case PENALTY -> loan.currentOutstanding().penalties();
                    case ITBMS, OTHER -> null;
                  };
              var balance =
                  buckets.stream()
                      .filter(
                          b ->
                              b.category() == category
                                  && b.currentClassification()
                                      == Classification.NATIVE_UNSEGREGATED)
                      .map(AccountBucket::netGlReceivable)
                      .filter(java.util.Objects::nonNull)
                      .findFirst()
                      .orElse(null);
              var difference =
                  asOf.equals(loan.businessDate()) && balance != null && nativeAmount != null
                      ? nativeAmount.subtract(balance)
                      : null;
              return new Reconciliation(
                  category,
                  nativeAmount,
                  balance,
                  difference,
                  difference == null
                      ? ReconciliationStatus.UNVERIFIED
                      : difference.signum() == 0
                          ? ReconciliationStatus.MATCHED_CURRENT_GL_ONLY
                          : ReconciliationStatus.DIFFERENCE);
            })
        .toList();
  }
}
