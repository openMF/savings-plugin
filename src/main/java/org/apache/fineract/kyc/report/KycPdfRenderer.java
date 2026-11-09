/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.kyc.report;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.data.ClientIdentifierData;
import org.openpdf.text.Document;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Renders stored facts; absent records never imply that identity checks have passed. */
@Component
public class KycPdfRenderer {
  private final BaseFont baseFont;

  public KycPdfRenderer(@Value("${mifos.kyc.pdf-font:}") String fontPath) {
    try {
      if (fontPath == null || fontPath.isBlank()) {
        try (var font = getClass().getResourceAsStream("/kyc/fonts/NotoSans-Regular.ttf")) {
          if (font == null) {
            throw new IllegalStateException("KYC report font is missing");
          }
          baseFont =
              BaseFont.createFont(
                  "NotoSans-Regular.ttf",
                  BaseFont.IDENTITY_H,
                  true,
                  true,
                  font.readAllBytes(),
                  null);
        }
      } else {
        baseFont = BaseFont.createFont(fontPath, BaseFont.IDENTITY_H, true);
      }
    } catch (Exception e) {
      throw new IllegalStateException("Cannot load the KYC PDF font", e);
    }
  }

  public byte[] render(
      ClientData client,
      List<ClientIdentifierData> identities,
      Map<String, Object> verification,
      Map<String, Object> validation,
      boolean canReadValidation) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Document doc = new Document(PageSize.A4, 40, 40, 36, 36);
    PdfWriter writer = PdfWriter.getInstance(doc, bytes);
    writer.setPdfVersion(PdfWriter.VERSION_1_7);
    doc.addTitle("KYC report - client " + client.getId());
    doc.addCreator("Mifos KYC reporting");
    doc.open();
    doc.add(new Paragraph("KYC REPORT", font(21, Font.BOLD)));
    doc.add(
        new Paragraph(
            "Client " + client.getId() + " | Generated " + Instant.now(), font(9, Font.NORMAL)));
    doc.add(
        new Paragraph(
            "Snapshot of saved client and verification records. Missing information is shown as Not"
                + " recorded.",
            font(9, Font.NORMAL)));
    section(doc, "Client information");
    PdfPTable profile = table(2);
    row(profile, "Client name", client.getDisplayName());
    row(profile, "Client ID", client.getId());
    row(profile, "Account number", client.getAccountNo());
    row(
        profile,
        "Legal form",
        client.getLegalForm() == null ? null : client.getLegalForm().getValue());
    row(profile, "Office", client.getOfficeName());
    row(profile, "Status", client.getStatus() == null ? null : client.getStatus().getValue());
    row(profile, "Activation date", client.getActivationDate());
    row(profile, "Date of birth", client.getDateOfBirth());
    row(profile, "Mobile number", client.getMobileNo());
    row(profile, "Email", client.getEmailAddress());
    if (client.getClientNonPersonDetails() != null) {
      row(profile, "Incorporation number", client.getClientNonPersonDetails().getIncorpNumber());
      row(
          profile,
          "Incorporation valid until",
          client.getClientNonPersonDetails().getIncorpValidityTillDate());
    }
    doc.add(profile);
    section(doc, "Identity documents");
    if (identities == null) {
      doc.add(
          new Paragraph(
              "Not included: identity read permission is required.", font(10, Font.NORMAL)));
    } else if (identities.isEmpty()) {
      doc.add(new Paragraph("No identity documents recorded.", font(10, Font.NORMAL)));
    } else {
      for (var identity : identities) {
        PdfPTable details = table(2);
        row(
            details,
            "Type",
            identity.getDocumentType() == null ? null : identity.getDocumentType().getName());
        row(details, "Document key", identity.getDocumentKey());
        row(details, "Status", identity.getStatus());
        row(details, "Issued", identity.getIssuanceDate());
        row(details, "Expires", identity.getExpiryDate());
        doc.add(details);
      }
    }
    section(doc, "External KYC verification");
    PdfPTable external = table(2);
    row(external, "Latest saved status", verification.get("kyc_status"));
    row(external, "Recorded at (UTC)", verification.get("created_on_utc"));
    doc.add(external);
    section(doc, "Document validation");
    if (!canReadValidation) {
      doc.add(
          new Paragraph(
              "Not included: validation read permission is required.", font(10, Font.NORMAL)));
    } else if (validation.isEmpty()) {
      doc.add(new Paragraph("No document validation recorded.", font(10, Font.NORMAL)));
    } else {
      doc.add(
          new Paragraph(
              "Saved validation status: " + value(validation.get("validation_status")),
              font(10, Font.NORMAL)));
      PdfPTable checks = table(6);
      for (String heading :
          List.of("Document", "Selected", "Missing", "Illegible", "Invalid", "Expired")) {
        cell(checks, heading, true);
      }
      String[] keys = {"nid", "legal_id", "proof", "score"};
      String[] names = {"National ID", "Legal ID", "Address proof", "Credit score"};
      for (int i = 0; i < keys.length; i++) {
        cell(checks, names[i], false);
        for (String check : List.of("selected", "missing", "illegible", "invalid", "expired")) {
          Object v = validation.get(keys[i] + "_" + check);
          cell(
              checks,
              v == null
                  ? "Not recorded"
                  : "1".equals(v.toString()) ? "Yes" : "0".equals(v.toString()) ? "No" : value(v),
              false);
        }
      }
      doc.add(checks);
    }
    doc.close();
    return bytes.toByteArray();
  }

  private Font font(float size, int style) {
    return new Font(baseFont, size, style);
  }

  private void section(Document doc, String title) {
    Paragraph heading = new Paragraph(title, font(12, Font.BOLD));
    heading.setSpacingBefore(13);
    heading.setSpacingAfter(6);
    doc.add(heading);
  }

  private PdfPTable table(int columns) {
    PdfPTable table = new PdfPTable(columns);
    table.setWidthPercentage(100);
    table.setSpacingAfter(5);
    table.setSplitLate(false);
    return table;
  }

  private void row(PdfPTable table, String label, Object v) {
    cell(table, label, true);
    cell(table, value(v), false);
  }

  private void cell(PdfPTable table, String text, boolean heading) {
    PdfPCell cell = new PdfPCell(new Phrase(text, font(9, heading ? Font.BOLD : Font.NORMAL)));
    cell.setPadding(6);
    cell.setBorderColor(new Color(220, 225, 230));
    if (heading) cell.setBackgroundColor(new Color(242, 245, 248));
    table.addCell(cell);
  }

  private String value(Object v) {
    return v == null || v.toString().isBlank() ? "Not recorded" : v.toString();
  }
}
