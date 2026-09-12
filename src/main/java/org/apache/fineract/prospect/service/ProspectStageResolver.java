/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.prospect.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.prospect.data.ProspectStageSummary;

public final class ProspectStageResolver {

  public static final String UNKNOWN = "UNKNOWN";
  private static final String COMPLETED = "COMPLETED";

  private ProspectStageResolver() {}

  public static ProspectStageSummary resolve(
      final String latestStageCode,
      final String latestStageStatus,
      final String lastCompletedStageCode) {
    final String currentStage = StringUtils.defaultIfBlank(latestStageCode, UNKNOWN);
    final String lastCompletedStage = StringUtils.defaultIfBlank(lastCompletedStageCode, UNKNOWN);
    final String stoppedAtStage =
        StringUtils.isBlank(latestStageCode) || COMPLETED.equalsIgnoreCase(latestStageStatus)
            ? UNKNOWN
            : latestStageCode;
    return new ProspectStageSummary(currentStage, lastCompletedStage, stoppedAtStage);
  }
}
