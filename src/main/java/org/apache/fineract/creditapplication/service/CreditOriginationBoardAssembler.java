/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.creditapplication.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.creditapplication.data.CreditOriginationAgingData;
import org.apache.fineract.creditapplication.data.CreditOriginationBoardData;
import org.apache.fineract.creditapplication.data.CreditOriginationScoreComponent;
import org.apache.fineract.creditapplication.data.CreditOriginationScoreComponentData;
import org.apache.fineract.creditapplication.data.CreditOriginationStageCode;
import org.apache.fineract.creditapplication.data.CreditOriginationStageData;
import org.apache.fineract.creditapplication.data.CreditOriginationStageDetailsData;

/** Pure board assembly shared by the individual board and the global dashboard. */
final class CreditOriginationBoardAssembler {

  static final String COMPLETED = "COMPLETED";
  static final int STALLED_THRESHOLD_DAYS = 7;
  static final String AGING_UNIT = "CALENDAR_DAYS";
  static final String AGING_STALLED = "STALLED";
  static final String AGING_ON_TIME = "ON_TIME";
  static final String AGING_UNKNOWN = "UNKNOWN";
  static final String AGING_NOT_APPLICABLE = "NOT_APPLICABLE";

  /** Final outcomes that remove an application from the active origination population. */
  static final Set<String> INACTIVE_STATUSES =
      Set.of("CANCELLED", "REJECTED", "WITHDRAWN", "WRITTEN_OFF");

  private static final Set<String> TERMINAL_STATUSES =
      Set.of("BLOCKED", "CANCELLED", "FAILED", "REJECTED", "WITHDRAWN", "WRITTEN_OFF");
  private static final String UNAVAILABLE = "UNAVAILABLE";
  private static final String EVENT_SOURCE = "m_credit_origination_stage_event";

  private CreditOriginationBoardAssembler() {}

  static CreditOriginationBoardData assemble(
      final ApplicationContext application,
      final Map<CreditOriginationStageCode, StageEvidence> suppliedEvidence,
      final EventHistory history,
      final LocalDate businessDate) {
    final Map<CreditOriginationStageCode, StageEvidence> evidence =
        new EnumMap<>(CreditOriginationStageCode.class);
    evidence.putAll(suppliedEvidence);

    CreditOriginationStageCode current = null;
    for (CreditOriginationStageCode code : CreditOriginationStageCode.values()) {
      if (!evidence(evidence, code).completed()) {
        current = code;
        break;
      }
    }

    final List<CreditOriginationStageData> stages = new ArrayList<>();
    Entry currentEntry = Entry.UNKNOWN;
    String currentStatus = null;
    for (CreditOriginationStageCode code : CreditOriginationStageCode.values()) {
      final StageEvidence item = evidence(evidence, code);
      final String status = status(code, current, item);
      final Entry entry = entry(code, evidence, history, application);
      if (code == current) {
        currentEntry = entry;
        currentStatus = status;
      }
      final boolean scoring = code == CreditOriginationStageCode.PARAMETRIC_SCORE;
      stages.add(
          new CreditOriginationStageData(
              code.name(),
              code.getDisplayName(),
              status,
              item.completed() ? item.completedOn() : null,
              item.details(),
              entry.on(),
              item.lastUpdatedOn(),
              scoring ? pendingScoreRequirements(item, history) : null,
              scoring ? scoreComponents(history) : null));
    }
    return new CreditOriginationBoardData(
        application.loanId(),
        application.clientId(),
        application.loanId(),
        application.prospectId(),
        current == null ? null : current.name(),
        List.copyOf(stages),
        application.officeId(),
        application.productId(),
        application.responsibleStaffId(),
        application.responsibleStaffName(),
        aging(current, currentStatus, currentEntry, businessDate));
  }

  /** Mandatory scoring components that have no COMPLETED evidence, in stable order. */
  static List<String> missingMandatoryComponents(
      final Map<CreditOriginationScoreComponent, String> latestStatuses) {
    return Arrays.stream(CreditOriginationScoreComponent.values())
        .filter(CreditOriginationScoreComponent::isRequired)
        .filter(component -> !COMPLETED.equals(latestStatuses.get(component)))
        .map(Enum::name)
        .toList();
  }

  static String normalize(final String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private static StageEvidence evidence(
      final Map<CreditOriginationStageCode, StageEvidence> evidence,
      final CreditOriginationStageCode code) {
    return evidence.getOrDefault(code, StageEvidence.missing());
  }

  private static String status(
      final CreditOriginationStageCode code,
      final CreditOriginationStageCode current,
      final StageEvidence evidence) {
    if (evidence.completed()) {
      return COMPLETED;
    }
    final String sourceStatus = normalize(evidence.sourceStatus());
    if (TERMINAL_STATUSES.contains(sourceStatus)) {
      return sourceStatus;
    }
    return code == current ? "CURRENT" : "PENDING";
  }

  /**
   * A phase is entered when every earlier phase has completed, i.e. at the latest earlier
   * completion. ONBOARDING is entered at its first event or prospect registration. A phase that was
   * completed and later reopened is re-entered at its first event after that completion. Any
   * missing prerequisite timestamp leaves the entry unknown; no other date is substituted.
   */
  private static Entry entry(
      final CreditOriginationStageCode code,
      final Map<CreditOriginationStageCode, StageEvidence> evidence,
      final EventHistory history,
      final ApplicationContext application) {
    final StageEvidence item = evidence(evidence, code);
    final StageTimeline timeline = history.timeline(code);
    final Entry base =
        code.ordinal() == 0
            ? onboardingEntry(timeline.firstEventOn(), application.prospectCreatedOn())
            : priorCompletionEntry(code, evidence);
    if (!item.completed()
        && timeline.reopenedOn() != null
        && (base.on() == null || timeline.reopenedOn().isAfter(base.on()))) {
      return new Entry(timeline.reopenedOn(), "STAGE_REOPENED");
    }
    if (item.completed()
        && base.on() != null
        && item.completedOn() != null
        && base.on().isAfter(item.completedOn())) {
      // Completed before its prerequisites: the entry moment is not established by evidence.
      return Entry.UNKNOWN;
    }
    return base;
  }

  private static Entry onboardingEntry(
      final OffsetDateTime firstEventOn, final OffsetDateTime prospectCreatedOn) {
    if (firstEventOn != null
        && (prospectCreatedOn == null || !prospectCreatedOn.isBefore(firstEventOn))) {
      return new Entry(firstEventOn, EVENT_SOURCE);
    }
    return prospectCreatedOn == null
        ? Entry.UNKNOWN
        : new Entry(prospectCreatedOn, "m_prospect_registration.created_on_utc");
  }

  private static Entry priorCompletionEntry(
      final CreditOriginationStageCode code,
      final Map<CreditOriginationStageCode, StageEvidence> evidence) {
    OffsetDateTime latest = null;
    for (CreditOriginationStageCode prior : CreditOriginationStageCode.values()) {
      if (prior == code) {
        break;
      }
      final StageEvidence item = evidence(evidence, prior);
      if (!item.completed() || item.completedOn() == null) {
        return Entry.UNKNOWN;
      }
      if (latest == null || item.completedOn().isAfter(latest)) {
        latest = item.completedOn();
      }
    }
    return new Entry(latest, "PRIOR_STAGE_COMPLETION");
  }

  private static CreditOriginationAgingData aging(
      final CreditOriginationStageCode current,
      final String currentStatus,
      final Entry entry,
      final LocalDate businessDate) {
    if (current == null) {
      return null;
    }
    Long days = null;
    if (entry.on() != null && businessDate != null) {
      final LocalDate entered = entry.on().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
      if (!entered.isAfter(businessDate)) {
        days = ChronoUnit.DAYS.between(entered, businessDate);
      }
    }
    final String status;
    if (current == CreditOriginationStageCode.RECOVERY
        || INACTIVE_STATUSES.contains(currentStatus)) {
      status = AGING_NOT_APPLICABLE;
    } else if (days == null) {
      status = AGING_UNKNOWN;
    } else {
      status = days > STALLED_THRESHOLD_DAYS ? AGING_STALLED : AGING_ON_TIME;
    }
    return new CreditOriginationAgingData(
        current.name(),
        entry.on(),
        entry.source(),
        businessDate,
        days,
        status,
        STALLED_THRESHOLD_DAYS,
        AGING_UNIT);
  }

  private static List<String> pendingScoreRequirements(
      final StageEvidence scoring, final EventHistory history) {
    if (scoring.completed()) {
      return List.of();
    }
    final Map<CreditOriginationScoreComponent, String> statuses =
        new EnumMap<>(CreditOriginationScoreComponent.class);
    for (CreditOriginationScoreComponent component : CreditOriginationScoreComponent.values()) {
      final ComponentEvidence item = history.component(component);
      if (item != null) {
        statuses.put(component, item.status());
      }
    }
    return missingMandatoryComponents(statuses);
  }

  private static List<CreditOriginationScoreComponentData> scoreComponents(
      final EventHistory history) {
    return Arrays.stream(CreditOriginationScoreComponent.values())
        .map(
            component -> {
              final ComponentEvidence item = history.component(component);
              return item == null
                  ? new CreditOriginationScoreComponentData(
                      component.name(),
                      component.getDisplayName(),
                      component.isRequired(),
                      UNAVAILABLE,
                      null,
                      null,
                      null,
                      null,
                      null)
                  : new CreditOriginationScoreComponentData(
                      component.name(),
                      component.getDisplayName(),
                      component.isRequired(),
                      item.status(),
                      item.source(),
                      item.sourceReference(),
                      item.occurredOn(),
                      item.recordedOn(),
                      item.reason());
            })
        .toList();
  }

  private static <E extends Enum<E>> E parse(final Class<E> type, final String value) {
    try {
      return Enum.valueOf(type, normalize(value));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  record StageEvidence(
      boolean completed,
      OffsetDateTime completedOn,
      String sourceStatus,
      CreditOriginationStageDetailsData details,
      OffsetDateTime lastUpdatedOn) {

    StageEvidence(
        final boolean completed,
        final OffsetDateTime completedOn,
        final String sourceStatus,
        final CreditOriginationStageDetailsData details) {
      this(completed, completedOn, sourceStatus, details, completedOn);
    }

    static StageEvidence missing() {
      return new StageEvidence(false, null, null, null, null);
    }
  }

  /** Identity, scope and ownership of one credit application. */
  record ApplicationContext(
      Long loanId,
      Long clientId,
      Long prospectId,
      OffsetDateTime prospectCreatedOn,
      Long officeId,
      Long productId,
      Long responsibleStaffId,
      String responsibleStaffName) {}

  /** One row of m_credit_origination_stage_event, stage-level when componentCode is null. */
  record EventRow(
      String stageCode,
      String componentCode,
      String status,
      OffsetDateTime occurredOn,
      OffsetDateTime recordedOn,
      Long actorId,
      String actorName,
      String source,
      String sourceReference,
      String reason) {}

  record ComponentEvidence(
      String status,
      OffsetDateTime occurredOn,
      OffsetDateTime recordedOn,
      String source,
      String sourceReference,
      String reason) {}

  record StageTimeline(OffsetDateTime firstEventOn, OffsetDateTime reopenedOn) {
    static final StageTimeline EMPTY = new StageTimeline(null, null);
  }

  private record Entry(OffsetDateTime on, String source) {
    static final Entry UNKNOWN = new Entry(null, null);
  }

  /**
   * Deterministic fold over one application's stage events. Rows must arrive newest first
   * (occurred_on_utc DESC, id DESC): the first row per stage or component is the latest evidence,
   * and repeated events inside a phase never move its entry timestamp.
   */
  static final class EventHistory {

    private final Map<CreditOriginationStageCode, StageEvidence> latest =
        new EnumMap<>(CreditOriginationStageCode.class);
    private final Map<CreditOriginationStageCode, TimelineFold> timelines =
        new EnumMap<>(CreditOriginationStageCode.class);
    private final Map<CreditOriginationScoreComponent, ComponentEvidence> components =
        new EnumMap<>(CreditOriginationScoreComponent.class);

    void accept(final EventRow row) {
      final CreditOriginationStageCode stage =
          parse(CreditOriginationStageCode.class, row.stageCode());
      if (stage == null || row.occurredOn() == null) {
        return;
      }
      final String status = normalize(row.status());
      if (row.componentCode() != null) {
        final CreditOriginationScoreComponent component =
            parse(CreditOriginationScoreComponent.class, row.componentCode());
        if (component != null && stage == CreditOriginationStageCode.PARAMETRIC_SCORE) {
          components.putIfAbsent(
              component,
              new ComponentEvidence(
                  status,
                  row.occurredOn(),
                  row.recordedOn(),
                  row.source(),
                  row.sourceReference(),
                  row.reason()));
        }
        return;
      }
      final boolean completed = COMPLETED.equals(status);
      latest.putIfAbsent(
          stage,
          new StageEvidence(
              completed,
              completed ? row.occurredOn() : null,
              status,
              new CreditOriginationStageDetailsData(
                  row.source(),
                  status,
                  row.actorId(),
                  row.actorName(),
                  row.sourceReference(),
                  row.reason(),
                  null,
                  null,
                  null,
                  null),
              row.occurredOn()));
      timelines
          .computeIfAbsent(stage, ignored -> new TimelineFold())
          .accept(completed, row.occurredOn());
    }

    StageEvidence latest(final CreditOriginationStageCode stage) {
      return latest.get(stage);
    }

    StageTimeline timeline(final CreditOriginationStageCode stage) {
      final TimelineFold fold = timelines.get(stage);
      return fold == null ? StageTimeline.EMPTY : fold.toTimeline();
    }

    ComponentEvidence component(final CreditOriginationScoreComponent component) {
      return components.get(component);
    }
  }

  private static final class TimelineFold {

    private OffsetDateTime firstEventOn;
    private OffsetDateTime lastCompletedOn;
    private OffsetDateTime reopenedOn;
    private OffsetDateTime openSince;

    void accept(final boolean completed, final OffsetDateTime occurredOn) {
      firstEventOn = occurredOn;
      if (lastCompletedOn != null) {
        return;
      }
      if (completed) {
        lastCompletedOn = occurredOn;
        reopenedOn = openSince;
      } else {
        openSince = occurredOn;
      }
    }

    StageTimeline toTimeline() {
      return new StageTimeline(firstEventOn, reopenedOn);
    }
  }
}
