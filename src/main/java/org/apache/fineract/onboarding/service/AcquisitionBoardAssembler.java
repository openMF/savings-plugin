/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.onboarding.data.AcquisitionBoardData;
import org.apache.fineract.onboarding.data.AcquisitionStageCode;
import org.apache.fineract.onboarding.data.AcquisitionStageData;
import org.apache.fineract.onboarding.data.AcquisitionStageDetailsData;

final class AcquisitionBoardAssembler {

  private static final Set<String> TERMINAL_STATUSES =
      Set.of("BLOCKED", "CANCELLED", "CLOSED", "FAILED", "REJECTED", "WITHDRAWN");

  private AcquisitionBoardAssembler() {}

  static AcquisitionBoardData assemble(
      final Long clientId,
      final Long accountId,
      final Long prospectId,
      final Map<AcquisitionStageCode, StageEvidence> evidenceByStage) {
    final Map<AcquisitionStageCode, StageEvidence> evidence =
        new EnumMap<>(AcquisitionStageCode.class);
    evidence.putAll(evidenceByStage);

    AcquisitionStageCode currentStage = null;
    for (AcquisitionStageCode code : AcquisitionStageCode.values()) {
      if (!evidence.getOrDefault(code, StageEvidence.missing()).completed()) {
        currentStage = code;
        break;
      }
    }

    final List<AcquisitionStageData> stages = new ArrayList<>();
    for (AcquisitionStageCode code : AcquisitionStageCode.values()) {
      final StageEvidence stageEvidence = evidence.getOrDefault(code, StageEvidence.missing());
      stages.add(
          new AcquisitionStageData(
              code.name(),
              code.getDisplayName(),
              boardStatus(code, currentStage, stageEvidence),
              stageEvidence.completed() ? stageEvidence.completedOn() : null,
              stageEvidence.details()));
    }
    return new AcquisitionBoardData(
        clientId,
        accountId,
        prospectId,
        currentStage == null ? null : currentStage.name(),
        List.copyOf(stages));
  }

  private static String boardStatus(
      final AcquisitionStageCode code,
      final AcquisitionStageCode currentStage,
      final StageEvidence evidence) {
    if (evidence.completed()) {
      return "COMPLETED";
    }
    final String sourceStatus = normalize(evidence.sourceStatus());
    if (TERMINAL_STATUSES.contains(sourceStatus)) {
      return sourceStatus;
    }
    return code == currentStage ? "CURRENT" : "PENDING";
  }

  private static String normalize(final String status) {
    return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
  }

  record StageEvidence(
      boolean completed,
      String completedOn,
      String sourceStatus,
      AcquisitionStageDetailsData details) {

    static StageEvidence missing() {
      return new StageEvidence(false, null, null, null);
    }
  }
}
