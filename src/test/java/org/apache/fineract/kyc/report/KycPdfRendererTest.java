/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.report;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

class KycPdfRendererTest {
  private final KycPdfRenderer renderer = new KycPdfRenderer("");

  private ClientData client() {
    ClientData client = new ClientData();
    client.setId(7L);
    client.setAccountNo("000000007");
    client.setDisplayName("María Müller");
    return client;
  }

  private String text(byte[] bytes) throws Exception {
    try (PdfReader reader = new PdfReader(bytes)) {
      return new PdfTextExtractor(reader).getTextFromPage(1);
    }
  }

  @Test
  void createsReadablePdfFromActualProfileWithoutInventingValidation() throws Exception {
    byte[] pdf = renderer.render(client(), List.of(), Map.of(), Map.of(), true);
    assertEquals("%PDF-1.7", new String(pdf, 0, 8, java.nio.charset.StandardCharsets.US_ASCII));
    String text = text(pdf);
    assertTrue(text.contains("María Müller"));
    assertTrue(text.contains("000000007"));
    assertTrue(text.contains("Not recorded"));
    assertTrue(text.contains("No document validation recorded"));
    assertFalse(text.contains("APPROVED"));
  }

  @Test
  void usesSavedValidationAndVerificationStatuses() throws Exception {
    String text =
        text(
            renderer.render(
                client(),
                List.of(),
                Map.of("kyc_status", "PENDING"),
                Map.of("validation_status", "REJECTED", "nid_missing", 1, "nid_selected", 0),
                true));
    assertTrue(text.contains("PENDING"));
    assertTrue(text.contains("REJECTED"));
    assertTrue(text.contains("Yes"));
    assertTrue(text.contains("No"));
  }

  @Test
  void deniedSectionsAreExplicitlyOmitted() throws Exception {
    String text = text(renderer.render(client(), null, Map.of(), Map.of(), false));
    assertTrue(text.contains("identity read permission is required"));
    assertTrue(text.contains("validation read permission is required"));
  }
}
