package com.example.ArtConnect.notification;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import com.example.ArtConnect.dto.MarketDtos.InvoiceResponse;
import com.example.ArtConnect.dto.MarketDtos.NotificationResponse;
import com.example.ArtConnect.dto.MarketDtos.OrderResponse;
import com.example.ArtConnect.exception.ApiException;
import com.example.ArtConnect.model.Order;
import com.example.ArtConnect.model.OrderSource;
import com.example.ArtConnect.model.User;
import com.example.ArtConnect.repository.NotificationRepository;
import com.example.ArtConnect.repository.OrderRepository;

/** Builds invoices for paid orders and renders them as an email and a text message. */
@Service
public class InvoiceService {
    private static final Locale INDIA = new Locale("en", "IN");

    private final OrderRepository orderRepository;
    private final NotificationRepository notificationRepository;
    private final String currency;
    private final String publicUrl;
    private final ZoneId zone;

    public InvoiceService(OrderRepository orderRepository, NotificationRepository notificationRepository,
                          @Value("${app.payments.currency:INR}") String currency,
                          @Value("${app.public-url:http://localhost:5173}") String publicUrl,
                          @Value("${app.timezone:Asia/Kolkata}") String timezone) {
        this.orderRepository = orderRepository;
        this.notificationRepository = notificationRepository;
        this.currency = currency;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.zone = ZoneId.of(timezone);
    }

    /** The invoice for the buyer, the seller, or an admin. */
    @Transactional(readOnly = true)
    public InvoiceResponse invoiceFor(User user, Long orderId) {
        Order order = paidOrder(orderId);
        if (!order.getBuyer().sameAs(user) && !order.getArtist().sameAs(user) && !user.isAdmin()) {
            throw ApiException.forbidden("You can only view invoices for your own orders");
        }
        return toInvoice(order);
    }

    /** Everything needed to send the invoice, loaded inside one transaction. */
    @Transactional(readOnly = true)
    public InvoiceMessage messageFor(Long orderId) {
        Order order = paidOrder(orderId);
        InvoiceResponse invoice = toInvoice(order);
        return new InvoiceMessage(order.getId(), order.getBillingEmail(), order.getBillingPhone(),
                "Your ArtConnect invoice " + invoice.invoiceNumber() + " for \"" + order.getArtworkTitle() + "\"",
                renderHtml(invoice), renderText(invoice), renderSms(invoice));
    }

    /** Rendered invoice plus its recipients. */
    public record InvoiceMessage(Long orderId, String email, String phone, String subject,
                                 String html, String text, String sms) {}

    private Order paidOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (order.getInvoiceNumber() == null) {
            throw ApiException.conflict("The invoice is available once the payment is complete");
        }
        return order;
    }

    private InvoiceResponse toInvoice(Order order) {
        User seller = order.getArtist();
        return new InvoiceResponse(order.getInvoiceNumber(), order.getPaidAt(), OrderResponse.from(order),
                seller.getDisplayName(), seller.getLocation(), order.getBuyer().getDisplayName(), currency,
                notificationRepository.findByOrderIdOrderByCreatedAtDesc(order.getId()).stream()
                        .map(NotificationResponse::from).toList());
    }

    String invoiceUrl(Long orderId) {
        return publicUrl + "/orders/" + orderId + "/invoice";
    }

    /** A short order confirmation; the full invoice goes by email. Plain ASCII ("Rs.") keeps it to one SMS. */
    String renderSms(InvoiceResponse invoice) {
        OrderResponse order = invoice.order();
        return "ArtConnect: Order #" + order.id() + " confirmed. Payment of Rs." + plainAmount(order.price())
                + " received; your invoice has been emailed to you.";
    }

    String renderText(InvoiceResponse invoice) {
        OrderResponse order = invoice.order();
        return """
                ArtConnect - Invoice %s
                Date: %s

                Billed to: %s (%s, %s)
                Sold by: %s

                %s (%s)    %s
                Total paid: %s

                Payment: %s, reference %s
                View or print your invoice: %s

                Thank you for supporting independent art!
                """.formatted(invoice.invoiceNumber(), date(invoice.issuedAt()),
                invoice.buyerName(), order.billingEmail(), order.billingPhone(), invoice.sellerName(),
                order.artworkTitle(), sourceLabel(order.source()), money(order.price()), money(order.price()),
                methodLabel(order.paymentMethod()), order.paymentId(), invoiceUrl(order.id()));
    }

    String renderHtml(InvoiceResponse invoice) {
        OrderResponse order = invoice.order();
        String seller = esc(invoice.sellerName()) + (invoice.sellerLocation() == null ? "" : "<br>" + esc(invoice.sellerLocation()));
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>Invoice %1$s</title></head>
                <body style="margin:0;padding:24px;background:#f6f4f0;font-family:Arial,Helvetica,sans-serif;color:#1a1a1a">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:640px;margin:0 auto;background:#ffffff;border:1px solid #e6e3de">
                  <tr><td style="padding:28px 32px;border-bottom:1px solid #e6e3de">
                    <div style="font-family:Georgia,serif;font-size:26px;font-weight:bold">ArtConnect</div>
                    <div style="margin-top:4px;color:#6f6f6f;font-size:13px">Payment received. Thank you for your purchase!</div>
                  </td></tr>
                  <tr><td style="padding:24px 32px">
                    <table role="presentation" width="100%%" style="font-size:14px">
                      <tr>
                        <td valign="top"><div style="color:#6f6f6f;font-size:12px;text-transform:uppercase;letter-spacing:1px">Invoice</div>
                          <div style="font-size:18px;font-weight:bold">%1$s</div>
                          <div style="color:#6f6f6f">%2$s</div></td>
                        <td valign="top" align="right"><div style="color:#6f6f6f;font-size:12px;text-transform:uppercase;letter-spacing:1px">Amount paid</div>
                          <div style="font-size:22px;font-weight:bold">%3$s</div></td>
                      </tr>
                    </table>
                    <table role="presentation" width="100%%" style="margin-top:24px;font-size:14px;line-height:1.5">
                      <tr>
                        <td valign="top" width="50%%"><div style="color:#6f6f6f;font-size:12px;text-transform:uppercase;letter-spacing:1px">Billed to</div>
                          %4$s<br>%5$s<br>%6$s</td>
                        <td valign="top" width="50%%"><div style="color:#6f6f6f;font-size:12px;text-transform:uppercase;letter-spacing:1px">Sold by</div>%7$s</td>
                      </tr>
                    </table>
                    <table role="presentation" width="100%%" cellpadding="10" cellspacing="0" style="margin-top:24px;font-size:14px;border-collapse:collapse">
                      <tr style="background:#f6f4f0;text-align:left"><th align="left">Item</th><th align="center">Qty</th><th align="right">Amount</th></tr>
                      <tr style="border-bottom:1px solid #e6e3de"><td><strong>%8$s</strong><br><span style="color:#6f6f6f">%9$s</span></td><td align="center">1</td><td align="right">%3$s</td></tr>
                      <tr><td colspan="2" align="right"><strong>Total</strong></td><td align="right"><strong>%3$s</strong></td></tr>
                    </table>
                    <p style="margin-top:24px;font-size:14px;line-height:1.6">Paid by <strong>%10$s</strong><br>
                      Payment reference: <code>%11$s</code></p>
                    <p style="margin:28px 0 8px"><a href="%12$s" style="display:inline-block;padding:12px 22px;background:#1a1a1a;color:#ffffff;text-decoration:none">View or print invoice</a></p>
                  </td></tr>
                  <tr><td style="padding:16px 32px;border-top:1px solid #e6e3de;color:#8a8a8a;font-size:12px">
                    This is a computer-generated invoice and does not need a signature.
                  </td></tr>
                </table>
                </body></html>
                """.formatted(esc(invoice.invoiceNumber()), esc(date(invoice.issuedAt())), esc(money(order.price())),
                esc(invoice.buyerName()), esc(order.billingEmail()), esc(order.billingPhone()), seller,
                esc(order.artworkTitle()), esc(sourceLabel(order.source())), esc(methodLabel(order.paymentMethod())),
                esc(order.paymentId()), esc(invoiceUrl(order.id())));
    }

    private String money(BigDecimal amount) {
        NumberFormat format = NumberFormat.getCurrencyInstance(INDIA);
        format.setCurrency(Currency.getInstance(currency));
        return format.format(amount);
    }

    private static String plainAmount(BigDecimal amount) {
        NumberFormat format = NumberFormat.getNumberInstance(INDIA);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(amount);
    }

    private String date(Instant instant) {
        return DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(zone).format(instant);
    }

    static String methodLabel(String method) {
        if (method == null) return "Online payment";
        return switch (method) {
            case "UPI" -> "UPI";
            case "CARD" -> "Debit / credit card";
            case "NETBANKING" -> "Net banking";
            case "WALLET" -> "Wallet";
            default -> "Online payment";
        };
    }

    private static String sourceLabel(OrderSource source) {
        return source == OrderSource.AUCTION ? "Original artwork, won at auction" : "Original artwork";
    }

    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
