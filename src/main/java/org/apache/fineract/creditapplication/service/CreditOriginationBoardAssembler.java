/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.creditapplication.data.CreditOriginationBoardData;
import org.apache.fineract.creditapplication.data.CreditOriginationStageCode;
import org.apache.fineract.creditapplication.data.CreditOriginationStageData;
import org.apache.fineract.creditapplication.data.CreditOriginationStageDetailsData;

final class CreditOriginationBoardAssembler {

  private static final Set<String> TERMINAL_STATUSES =
      Set.of("BLOCKED", "CANCELLED", "FAILED", "REJECTED", "WITHDRAWN", "WRITTEN_OFF");

  private CreditOriginationBoardAssembler() {}

  static CreditOriginationBoardData assemble(
      final Long loanId,
      final Long clientId,
      final Long prospectId,
      final Map<CreditOriginationStageCode, StageEvidence> suppliedEvidence) {
    final Map<CreditOriginationStageCode, StageEvidence> evidence =
        new EnumMap<>(CreditOriginationStageCode.class);
    evidence.putAll(suppliedEvidence);

    CreditOriginationStageCode current = null;
    for (CreditOriginationStageCode code : CreditOriginationStageCode.values()) {
      final StageEvidence item = evidence.getOrDefault(code, StageEvidence.missing());
      if (!item.completed()) {
        current = code;
        break;
      }
    }

    final List<CreditOriginationStageData> stages = new ArrayList<>();
    for (CreditOriginationStageCode code : CreditOriginationStageCode.values()) {
      final StageEvidence item = evidence.getOrDefault(code, StageEvidence.missing());
      stages.add(
          new CreditOriginationStageData(
              code.name(),
              code.getDisplayName(),
              status(code, current, item),
              item.completed() ? item.completedOn() : null,
              item.details()));
    }
    return new CreditOriginationBoardData(
        loanId,
        clientId,
        loanId,
        prospectId,
        current == null ? null : current.name(),
        List.copyOf(stages));
  }

  private static String status(
      final CreditOriginationStageCode code,
      final CreditOriginationStageCode current,
      final StageEvidence evidence) {
    if (evidence.completed()) {
      return "COMPLETED";
    }
    final String sourceStatus = normalize(evidence.sourceStatus());
    if (TERMINAL_STATUSES.contains(sourceStatus)) {
      return sourceStatus;
    }
    return code == current ? "CURRENT" : "PENDING";
  }

  private static String normalize(final String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  record StageEvidence(
      boolean completed,
      OffsetDateTime completedOn,
      String sourceStatus,
      CreditOriginationStageDetailsData details) {

    static StageEvidence missing() {
      return new StageEvidence(false, null, null, null);
    }
  }
}
