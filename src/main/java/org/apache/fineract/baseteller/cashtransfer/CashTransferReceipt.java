package org.apache.fineract.baseteller.cashtransfer;

/** Self-contained printable receipt rendered exclusively from the authorized persisted snapshot. */
public final class CashTransferReceipt {
  private CashTransferReceipt() {}

  public static String html(CashTransferData data) {
    StringBuilder html =
        new StringBuilder(
            "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Cash transfer"
                + " receipt</title><style>body{font:16px sans-serif;max-width:800px;margin:2em"
                + " auto}table{border-collapse:collapse;width:100%}th,td{padding:.5em;border:1px"
                + " solid #aaa;text-align:left}@media print{body{margin:0}}</style><body><h1>Cash"
                + " transfer receipt</h1>");
    line(html, "Reference", data.reference());
    line(html, "Status", data.status());
    line(html, "Business date", data.businessDate());
    line(html, "Completed at (UTC)", data.completedAt());
    line(html, "Office", data.source().officeName());
    line(
        html,
        "Source teller / cashier",
        data.source().tellerName() + " / " + data.source().cashierName());
    line(
        html,
        "Destination teller / cashier",
        data.destination().tellerName() + " / " + data.destination().cashierName());
    line(html, "Performed by", data.createdByUsername());
    for (var currency : data.currencies()) {
      html.append("<h2>")
          .append(escape(currency.currencyCode()))
          .append(
              "</h2><table><thead><tr><th>Denomination</th><th>Type</th><th>Value</th><th>Quantity</th><th>Amount</th></tr></thead><tbody>");
      for (var d : currency.denominations()) {
        html.append("<tr>");
        for (Object value :
            new Object[] {
              d.denominationId(),
              d.denominationType(),
              d.denominationValue(),
              d.quantity(),
              d.amount()
            }) html.append("<td>").append(escape(String.valueOf(value))).append("</td>");
        html.append("</tr>");
      }
      html.append("</tbody></table>");
      line(html, "Total " + currency.currencyCode(), currency.total().toPlainString());
    }
    return html.append("</body></html>").toString();
  }

  private static void line(StringBuilder html, String label, String value) {
    html.append("<p><strong>")
        .append(escape(label))
        .append(": </strong>")
        .append(escape(value))
        .append("</p>");
  }

  private static String escape(String value) {
    return value == null
        ? ""
        : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
  }
}
