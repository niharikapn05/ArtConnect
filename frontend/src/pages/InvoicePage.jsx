import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { request, useApi } from "../api.js";
import { useAuth } from "../auth.jsx";
import { PageState } from "../components.jsx";
import { formatDateTime, formatPrice } from "../format.js";
import { formatPhone, paymentMethodLabel } from "../payment.jsx";

const STATUS_TEXT = {
  SENT: "Delivered to provider",
  SIMULATED: "Demo outbox (not really sent)",
  FAILED: "Failed",
};

/** A printable invoice for a paid order, plus where it was emailed and texted. */
export default function InvoicePage() {
  const { id } = useParams();
  const { user } = useAuth();
  const { data: invoice, setData, loading, error } = useApi(`/api/orders/${encodeURIComponent(id)}/invoice`);
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState("");

  async function resend() {
    setSending(true);
    setNotice("");
    try {
      setData(await request(`/api/orders/${encodeURIComponent(id)}/invoice/send`, { method: "POST" }));
      setNotice("Invoice sent again.");
    } catch (sendError) {
      setNotice(sendError.message);
    } finally {
      setSending(false);
    }
  }

  return (
    <section className="container page-section">
      <PageState loading={loading} error={error}>
        {invoice && (
          <>
            <div className="invoice-toolbar no-print">
              <Link to={user?.id === invoice.order.buyerId ? "/collection" : "/studio?tab=sales"}>
                <i className="fas fa-arrow-left" aria-hidden="true" /> Back to orders
              </Link>
              <div className="d-flex gap-2">
                {user?.id === invoice.order.buyerId && (
                  <button type="button" className="btn btn-outline-dark btn-sm" onClick={resend} disabled={sending}>
                    {sending ? "Sending..." : "Resend invoice"}
                  </button>
                )}
                <button type="button" className="btn btn-primary btn-sm" onClick={() => window.print()}>
                  <i className="fas fa-print" aria-hidden="true" /> Print / Save as PDF
                </button>
              </div>
            </div>
            {notice && <p className="text-success-light no-print" role="status">{notice}</p>}

            <article className="invoice">
              <header className="invoice-header">
                <div>
                  <span className="brand">ArtConnect</span>
                  <p>Original art from independent artists</p>
                </div>
                <div className="invoice-meta">
                  <h1>Invoice</h1>
                  <p><strong>{invoice.invoiceNumber}</strong></p>
                  <p>{formatDateTime(invoice.issuedAt)}</p>
                  <span className="invoice-paid">Paid</span>
                </div>
              </header>

              <div className="invoice-parties">
                <div>
                  <h2>Billed to</h2>
                  <p>{invoice.buyerName}<br />{invoice.order.billingEmail}<br />{formatPhone(invoice.order.billingPhone)}</p>
                </div>
                <div>
                  <h2>Sold by</h2>
                  <p>{invoice.sellerName}{invoice.sellerLocation && <><br />{invoice.sellerLocation}</>}</p>
                </div>
                <div>
                  <h2>Order</h2>
                  <p>#{invoice.order.id}<br />{invoice.order.source === "AUCTION" ? "Won at auction" : "Purchase"}</p>
                </div>
              </div>

              <table className="invoice-items">
                <thead>
                  <tr><th>Item</th><th className="text-center">Qty</th><th className="text-end">Amount</th></tr>
                </thead>
                <tbody>
                  <tr>
                    <td><strong>{invoice.order.artworkTitle}</strong><br /><span>Original artwork</span></td>
                    <td className="text-center">1</td>
                    <td className="text-end">{formatPrice(invoice.order.price)}</td>
                  </tr>
                </tbody>
                <tfoot>
                  <tr><td colSpan="2" className="text-end">Total paid ({invoice.currency})</td><td className="text-end">{formatPrice(invoice.order.price)}</td></tr>
                </tfoot>
              </table>

              <p className="invoice-payment">
                Paid by <strong>{paymentMethodLabel(invoice.order.paymentMethod)}</strong> on {formatDateTime(invoice.order.paidAt)}
                {" "}· Payment reference <code>{invoice.order.paymentId}</code>
              </p>
              <p className="invoice-footnote">This is a computer-generated invoice and does not need a signature.</p>
            </article>

            <section className="invoice-deliveries no-print">
              <h2>Invoice delivery</h2>
              {invoice.notifications.length ? (
                <ul>
                  {invoice.notifications.map((notification, index) => (
                    <li key={index}>
                      <i className={`fas ${notification.channel === "EMAIL" ? "fa-envelope" : "fa-comment-sms"}`} aria-hidden="true" />
                      <span>
                        {notification.channel === "EMAIL" ? "Email" : "SMS"} to <strong>{notification.channel === "SMS" ? formatPhone(notification.recipient) : notification.recipient}</strong>
                        <small> · {formatDateTime(notification.createdAt)} · via {notification.provider}</small>
                      </span>
                      <span className={`delivery-status ${notification.status.toLowerCase()}`}>{STATUS_TEXT[notification.status]}</span>
                      {notification.error && <small className="text-danger d-block">{notification.error}</small>}
                    </li>
                  ))}
                </ul>
              ) : <p className="empty-state">The invoice is being sent…</p>}
            </section>
          </>
        )}
      </PageState>
    </section>
  );
}
