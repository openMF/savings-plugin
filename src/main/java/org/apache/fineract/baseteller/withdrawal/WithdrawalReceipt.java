package org.apache.fineract.baseteller.withdrawal;

/** Historical receipt markup. Every dynamic value is escaped before entering HTML. */
public final class WithdrawalReceipt {
  private WithdrawalReceipt() {}

  public static String html(WithdrawalData r) {
    StringBuilder rows = new StringBuilder();
    for (var d : r.denominations())
      rows.append("<tr><td>")
          .append(escape(d.denominationType()))
          .append("</td><td>")
          .append(escape(d.denominationValue()))
          .append("</td><td>")
          .append(d.quantity())
          .append("</td><td>")
          .append(escape(d.lineAmount()))
          .append("</td></tr>");
    return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Withdrawal"
        + " receipt</title></head><body><h1>Withdrawal receipt</h1><p>"
        + escape(r.reference())
        + "</p><p>"
        + escape(r.status())
        + " · "
        + escape(r.businessDate())
        + " · "
        + escape(r.completedAt())
        + "</p><p>Cashier: "
        + escape(r.cashier().cashierName())
        + " · "
        + escape(r.cashier().officeName())
        + "</p><p>Customer: "
        + escape(r.customer().displayName())
        + " · "
        + escape(r.customer().clientNumber())
        + "</p><p>Account: "
        + escape(r.account().accountNumber())
        + "</p><p>"
        + escape(r.method())
        + " "
        + escape(r.amount())
        + " "
        + escape(r.currencyCode())
        + "</p><p>Recipient: "
        + escape(r.recipient().identificationType())
        + " "
        + escape(r.recipient().maskedReference())
        + "</p><table><thead><tr><th>Type</th><th>Value</th>"
        + "<th>Quantity</th><th>Amount</th></tr></thead><tbody>"
        + rows
        + "</tbody></table></body></html>";
  }

  private static String escape(Object v) {
    return v == null
        ? ""
        : v.toString()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
  }
}
