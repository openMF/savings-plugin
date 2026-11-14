/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.anpa;

import static org.apache.fineract.anpa.AnpaAccountingData.*;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * bc1c598 bulk NPA updates have no transition event/history contract. Current state is observation
 * only.
 */
@Component
public class AnpaHistoryService {
  History resolve(Loan loan, LocalDate asOf) {
    var start = loan.disbursedOn() == null ? loan.submittedOn() : loan.disbursedOn();
    var unknown =
        start == null || start.isAfter(asOf)
            ? List.<Period>of()
            : List.of(
                new Period(
                    start,
                    asOf,
                    Classification.UNKNOWN,
                    "No authoritative NPA transition history in native bc1c598"));
    return new History(
        loan.currentNpa(),
        loan.businessDate(),
        List.of(),
        List.of(),
        unknown,
        List.of(Reason.NPA_HISTORY_UNAVAILABLE));
  }
}
