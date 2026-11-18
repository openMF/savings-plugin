/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.investment;

import static org.apache.fineract.investment.InvestmentRateData.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Pure snapshot resolver, deliberately independent of drafts and native account execution. */
public final class InvestmentRateResolver {
  private InvestmentRateResolver() {}

  public static Selection select(
      long product, Configuration version, BigDecimal amount, int term, Instant at) {
    if (version == null)
      return missing(product, null, amount, term, at, "NO_PUBLISHED_RATE_AT_REQUESTED_INSTANT");
    var policy = version.policySnapshot();
    new InvestmentProductValidation().validateTerm(policy, term);
    InvestmentRateValidation.money(amount, version.nativeSnapshot(), "investmentAmount");
    var matrix = version.configuration().matrix();
    var matches =
        matrix.cells().stream()
            .filter(
                c -> {
                  var a = matrix.amountBands().get(c.amountBand());
                  var t = matrix.termBands().get(c.termBand());
                  return amount.compareTo(a.lower()) >= 0
                      && (a.upper() == null
                          || amount.compareTo(a.upper()) < 0
                          || (a.upperInclusive() && amount.compareTo(a.upper()) == 0))
                      && term >= t.minimumDays()
                      && term <= t.maximumDays();
                })
            .toList();
    if (matches.size() != 1)
      return missing(
          product,
          version,
          amount,
          term,
          at,
          matches.isEmpty() ? "UNCOVERED_AMOUNT_OR_TERM" : "AMBIGUOUS_RATE_CONFIGURATION");
    var c = matches.getFirst();
    return new Selection(
        product,
        version.investmentType(),
        version.currencyCode(),
        amount,
        term,
        at.toString(),
        c.annualRatePercent(),
        version.version(),
        matrix.amountBands().get(c.amountBand()),
        matrix.termBands().get(c.termBand()),
        version.effectiveDate(),
        version.effectiveInstant(),
        version.timezone(),
        null,
        SelectionStatus.SELECTED_CONFIGURATION_ONLY,
        capabilities(),
        financialBlockers());
  }

  private static Selection missing(
      long product, Configuration v, BigDecimal amount, int term, Instant at, String blocker) {
    var blockers = new ArrayList<>(financialBlockers());
    blockers.add(blocker);
    return new Selection(
        product,
        v == null ? null : v.investmentType(),
        v == null ? null : v.currencyCode(),
        amount,
        term,
        at.toString(),
        null,
        v == null ? null : v.version(),
        null,
        null,
        v == null ? null : v.effectiveDate(),
        v == null ? null : v.effectiveInstant(),
        v == null ? null : v.timezone(),
        null,
        SelectionStatus.NO_APPLICABLE_RATE,
        capabilities(),
        List.copyOf(blockers));
  }

  static List<CellChange> changes(WriteRequest before, WriteRequest after) {
    var old = indexed(before);
    var next = indexed(after);
    var keys = new java.util.LinkedHashSet<>(old.keySet());
    keys.addAll(next.keySet());
    var changes = new ArrayList<CellChange>();
    for (var key : keys) {
      var a = old.get(key);
      var b = next.get(key);
      if (a == null || b == null || a.newRate().compareTo(b.newRate()) != 0)
        changes.add(
            new CellChange(
                b == null ? a.amountBand() : b.amountBand(),
                b == null ? a.termBand() : b.termBand(),
                a == null ? null : a.newRate(),
                b == null ? null : b.newRate()));
    }
    return List.copyOf(changes);
  }

  private static LinkedHashMap<String, CellChange> indexed(WriteRequest request) {
    var result = new LinkedHashMap<String, CellChange>();
    if (request == null) return result;
    var m = request.matrix();
    for (var c : m.cells()) {
      var a = m.amountBands().get(c.amountBand());
      var t = m.termBands().get(c.termBand());
      String key =
          a.lower().stripTrailingZeros().toPlainString()
              + ":"
              + (a.upper() == null ? "null" : a.upper().stripTrailingZeros().toPlainString())
              + ":"
              + a.upperInclusive()
              + ":"
              + t.minimumDays()
              + ":"
              + t.maximumDays();
      result.put(key, new CellChange(a, t, null, c.annualRatePercent()));
    }
    return result;
  }
}
