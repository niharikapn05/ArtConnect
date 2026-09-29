import { useCallback, useEffect, useRef, useState } from "react";
import { request } from "./api.js";
import { formatPrice, timeLeft, useNow } from "./format.js";

const RAZORPAY_SCRIPT = "https://checkout.razorpay.com/v1/checkout.js";

/** Thrown when the buyer closes the payment window without paying. */
export class PaymentCancelled extends Error {
  constructor(message = "Payment cancelled") {
    super(message);
    this.name = "PaymentCancelled";
  }
}

let razorpayScript = null;

function loadRazorpay() {
  if (window.Razorpay) return Promise.resolve();
  if (!razorpayScript) {
    razorpayScript = new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = RAZORPAY_SCRIPT;
      script.onload = resolve;
      script.onerror = () => {
        razorpayScript = null;
        reject(new Error("Couldn't load the payment window. Check your connection and try again."));
      };
      document.body.appendChild(script);
    });
  }
  return razorpayScript;
}

/** Opens Razorpay Checkout; resolves with the ids and signature the server needs to verify the payment. */
async function openRazorpay(checkout, buyerName) {
  await loadRazorpay();
  // Close the window before our reservation lapses, so nobody pays for an artwork that was released
  const secondsLeft = checkout.holdExpiresAt
    ? Math.floor((new Date(checkout.holdExpiresAt).getTime() - Date.now()) / 1000) - 60
    : null;
  if (secondsLeft !== null && secondsLeft < 60) {
    throw new Error("Your reservation has almost expired. Please start the purchase again.");
  }

  return new Promise((resolve, reject) => {
    let lastError = null;
    const razorpay = new window.Razorpay({
      key: checkout.keyId,
      order_id: checkout.providerOrderId,
      amount: checkout.amount,
      currency: checkout.currency,
      name: "ArtConnect",
      description: checkout.artworkTitle,
      prefill: { name: buyerName },
      theme: { color: "#9d4edd" },
      ...(secondsLeft !== null && { timeout: secondsLeft }),
      handler: (response) => resolve({
        providerOrderId: response.razorpay_order_id,
        paymentId: response.razorpay_payment_id,
        signature: response.razorpay_signature,
      }),
      modal: {
        // Razorpay lets the buyer retry inside the window after a failure, so only give up when it closes
        ondismiss: () => reject(lastError ?? new PaymentCancelled()),
      },
    });
    razorpay.on("payment.failed", (response) => {
      lastError = new Error(response.error?.description || "The payment failed. Please try again.");
    });
    razorpay.open();
  });
}

/**
 * Payment for a checkout returned by the API (POST /api/artworks/{id}/purchase or /api/orders/{id}/checkout).
 * Render `paymentDialog` somewhere in the component; it shows the demo-mode payment screen when needed.
 */
export function usePayment() {
  const [demoCheckout, setDemoCheckout] = useState(null);

  const payForCheckout = useCallback(async (checkout) => {
    const confirmation = checkout.provider === "mock"
      ? await new Promise((resolve, reject) => setDemoCheckout({ checkout, resolve, reject }))
      : await openRazorpay(checkout, checkout.buyerName);
    return request(`/api/orders/${checkout.orderId}/payment`, { method: "POST", json: confirmation });
  }, []);

  const paymentDialog = demoCheckout && (
    <DemoPaymentDialog
      checkout={demoCheckout.checkout}
      onPay={(confirmation) => { setDemoCheckout(null); demoCheckout.resolve(confirmation); }}
      onCancel={() => { setDemoCheckout(null); demoCheckout.reject(new PaymentCancelled()); }}
    />
  );

  return { payForCheckout, paymentDialog };
}

/** Stand-in for the gateway's window when the server runs with PAYMENT_PROVIDER=mock. No money moves. */
function DemoPaymentDialog({ checkout, onPay, onCancel }) {
  const payButton = useRef(null);
  const cancelRef = useRef(onCancel);
  const now = useNow(1000);

  useEffect(() => {
    cancelRef.current = onCancel;
  }, [onCancel]);

  // Runs once: the countdown re-renders every second and must not steal focus back
  useEffect(() => {
    payButton.current?.focus();
    const onKey = (event) => { if (event.key === "Escape") cancelRef.current(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  function pay() {
    const random = Math.random().toString(36).slice(2, 12);
    onPay({ providerOrderId: checkout.providerOrderId, paymentId: `mock_pay_${random}`, signature: "demo" });
  }

  return (
    <div className="payment-backdrop" role="presentation" onClick={onCancel}>
      <div className="payment-dialog" role="dialog" aria-modal="true" aria-labelledby="payment-title"
        onClick={(event) => event.stopPropagation()}>
        <p className="payment-demo-banner">Demo payment: no real money is charged</p>
        <h2 id="payment-title">Complete your purchase</h2>
        <p className="payment-item">{checkout.artworkTitle}</p>
        <p className="payment-amount">{formatPrice(checkout.amount / 100)}</p>
        {checkout.holdExpiresAt && (
          <p className="payment-hold">Reserved for you for {timeLeft(checkout.holdExpiresAt, now)}</p>
        )}
        <p className="payment-note">
          The server is running in demo mode. Set <code>PAYMENT_PROVIDER=razorpay</code> with Razorpay keys to accept
          real UPI, card and netbanking payments.
        </p>
        <div className="d-flex gap-2">
          <button ref={payButton} type="button" className="btn btn-success flex-grow-1" onClick={pay}>
            Pay {formatPrice(checkout.amount / 100)}
          </button>
          <button type="button" className="btn btn-outline-secondary" onClick={onCancel}>Cancel</button>
        </div>
      </div>
    </div>
  );
}
