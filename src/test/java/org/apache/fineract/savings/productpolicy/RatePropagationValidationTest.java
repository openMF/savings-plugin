/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.savings.productpolicy;

import static org.apache.fineract.savings.productpolicy.RatePropagationValidation.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RatePropagationValidationTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "[]",
        "{",
        "{policyVersion:1}",
        "{\"policyVersion\":0,\"effectiveDate\":\"2026-11-05\"}",
        "{\"policyVersion\":1.0,\"effectiveDate\":\"2026-11-05\"}",
        "{\"policyVersion\":\"1\",\"effectiveDate\":\"2026-11-05\"}",
        "{\"policyVersion\":1,\"effectiveDate\":\"2026-02-30\"}",
        "{\"policyVersion\":1,\"effectiveDate\":\"2026-11-05\",\"actorId\":1}",
        "{\"policyVersion\":1,\"policyVersion\":2,\"effectiveDate\":\"2026-11-05\"}"
      })
  void invalidPlansAndIdentityInjectionFail(String json) {
    assertThrows(RuntimeException.class, () -> plan(json));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"expectedRevision\":0}",
        "{\"expectedRevision\":1,\"reason\":\"\"}",
        "{\"expectedRevision\":1,\"checkerId\":2}",
        "{\"expectedRevision\":1,\"reason\":\"bad\\ntext\"}"
      })
  void invalidActionsFail(String json) {
    assertThrows(RuntimeException.class, () -> action(json, false));
  }

  @Test
  void reviewRequiresEvidenceAndBoundedCanonicalRequests() {
    assertEquals(1, plan("{\"policyVersion\":1,\"effectiveDate\":\"2026-11-05\"}").policyVersion());
    assertThrows(RuntimeException.class, () -> action("{\"expectedRevision\":1}", true));
    assertEquals(
        "Reviewed", action("{\"expectedRevision\":1,\"reason\":\"Reviewed\"}", true).reason());
    String id = "2f4d497e-bfff-4100-ad8c-f82a7f3c2f66";
    assertEquals(
        id,
        create(
                "{\"requestKey\":\""
                    + id
                    + "\",\"policyVersion\":1,\"effectiveDate\":\"2026-11-05\"}")
            .requestKey());
    assertThrows(RuntimeException.class, () -> uuid(id.toUpperCase(), "requestKey"));
    assertThrows(RuntimeException.class, () -> uuid("invalid", "batchId"));
    for (int n : new int[] {0, -1, 101, 501, Integer.MAX_VALUE})
      assertThrows(RuntimeException.class, () -> limit(n, 100));
    assertEquals(100, limit(100, 100));
    assertEquals(hash("same"), hash("same"));
    assertNotEquals(hash("same"), hash("changed"));
  }
}
