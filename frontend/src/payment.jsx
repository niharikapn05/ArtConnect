import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import QRCode from "qrcode";
import { mediaUrl, request } from "./api.js";
import { useAuth } from "./auth.jsx";
import { formatPrice, timeLeft, useNow } from "./format.js";

const RAZORPAY_SCRIPT = "https://checkout.razorpay.com/v1/checkout.js";
const CONTACT_KEY = "artconnect.billing";

/** Thrown when the buyer closes the payment window without paying. */
export class PaymentCancelled extends Error {
  constructor(message = "Payment cancelled") {
    super(message);
    this.name = "PaymentCancelled";
  }
}

/**
 * Checkout for one item. `openCheckout({ title, amount, imageUrl, subtitle, startCheckout })` opens the dialog;
 * startCheckout({ email }) must call the API (purchase or order checkout) and return its checkout details.
 * Resolves with { status: "paid", order } or { status: "closed", reserved } once the dialog closes.
 * Render `checkoutDialog` somewhere in the component.
 */
export function useCheckout() {
  const [session, setSession] = useState(null);

  const openCheckout = useCallback((options) => new Promise((resolve) => setSession({ ...options, resolve })), []);

  const checkoutDialog = session && (
    <CheckoutDialog
      {...session}
      onClose={(result) => {
        setSession(null);
        session.resolve(result);
      }}
    />
  );

  return { openCheckout, checkoutDialog };
}

function loadSavedEmail() {
  try {
    return JSON.parse(localStorage.getItem(CONTACT_KEY))?.email ?? "";
  } catch {
    return "";
  }
}

function saveEmail(email) {
  try {
    localStorage.setItem(CONTACT_KEY, JSON.stringify({ email }));
  } catch {
    // Not remembering the email is harmless.
  }
}

const METHOD_LABELS = { UPI: "UPI", CARD: "Card", NETBANKING: "Net banking", WALLET: "Wallet", ONLINE: "Online payment" };
export function paymentMethodLabel(method) {
  return METHOD_LABELS[method] ?? "Online payment";
}

function CheckoutDialog({ title, subtitle, amount, imageUrl, startCheckout, onClose }) {
  const { user } = useAuth();
  const [step, setStep] = useState("contact");
  const [email, setEmail] = useState(loadSavedEmail);
  const [checkout, setCheckout] = useState(null);
  const [order, setOrder] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const close = useCallback(() => {
    onClose(order ? { status: "paid", order } : { status: "closed", reserved: Boolean(checkout) });
  }, [onClose, order, checkout]);

  useEffect(() => {
    const onKey = (event) => {
      if (event.key === "Escape" && step !== "verifying") close();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [close, step]);

  async function verify(confirmation) {
    setStep("verifying");
    setError("");
    try {
      const paid = await request(`/api/orders/${checkout?.orderId ?? confirmation.orderId}/payment`, {
        method: "POST",
        json: { providerOrderId: confirmation.providerOrderId, paymentId: confirmation.paymentId,
          signature: confirmation.signature, method: confirmation.method },
      });
      setOrder(paid);
      setStep("success");
    } catch (verifyError) {
      setError(verifyError.message);
      setStep("failed");
    }
  }

  async function continueToPayment(event) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const details = { email: email.trim() };
      const started = await startCheckout(details);
      saveEmail(details.email);
      setCheckout(started);
      if (started.provider === "razorpay") {
        setStep("razorpay");
        try {
          const confirmation = await openRazorpay(started);
          await verify({ ...confirmation, orderId: started.orderId });
        } catch (razorpayError) {
          if (razorpayError instanceof PaymentCancelled) {
            setStep("cancelled");
          } else {
            setError(razorpayError.message);
            setStep("failed");
          }
        }
      } else {
        setStep("gateway");
      }
    } catch (startError) {
      setError(startError.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="payment-backdrop" role="presentation">
      <div className={`checkout-dialog ${step === "gateway" ? "wide" : ""}`} role="dialog" aria-modal="true" aria-labelledby="checkout-title">
        {step !== "verifying" && (
          <button type="button" className="checkout-close" aria-label="Close" onClick={close}>
            <i className="fas fa-xmark" aria-hidden="true" />
          </button>
        )}

        {step === "contact" && (
          <form onSubmit={continueToPayment}>
            <h2 id="checkout-title">Checkout</h2>
            <div className="checkout-item">
              {imageUrl && <img src={mediaUrl(imageUrl)} alt="" />}
              <div>
                <strong>{title}</strong>
                {subtitle && <span>{subtitle}</span>}
              </div>
              <span className="checkout-amount">{formatPrice(amount)}</span>
            </div>
            <p className="checkout-note">Your invoice will be emailed to this address.</p>
            <label className="form-label" htmlFor="checkout-email">Email</label>
            <input id="checkout-email" className="form-control mb-3" type="email" required autoComplete="email"
              placeholder="you@example.com" value={email} onChange={(event) => setEmail(event.target.value)} />
            {error && <p className="text-danger" role="alert">{error}</p>}
            <button type="submit" className="btn btn-primary btn-lg w-100" disabled={busy}>
              {busy ? "Please wait..." : `Continue to pay ${formatPrice(amount)}`}
            </button>
            <p className="checkout-secure"><i className="fas fa-lock" aria-hidden="true" /> Secure payment · {user?.displayName}</p>
          </form>
        )}

        {step === "gateway" && checkout && (
          <DemoGateway
            checkout={checkout}
            onPaid={verify}
            onFailed={(message) => { setError(message); setStep("failed"); }}
          />
        )}

        {step === "razorpay" && (
          <div className="checkout-status">
            <div className="spinner-border" role="status" aria-hidden="true" />
            <h2 id="checkout-title">Complete the payment in the Razorpay window</h2>
            <p>Pay with UPI, cards, netbanking or wallets. Keep this page open.</p>
          </div>
        )}

        {step === "verifying" && (
          <div className="checkout-status">
            <div className="spinner-border" role="status" aria-hidden="true" />
            <h2 id="checkout-title">Confirming your payment…</h2>
            <p>Please don&rsquo;t close this window.</p>
          </div>
        )}

        {step === "success" && order && (
          <div className="checkout-status success">
            <span className="checkout-tick" aria-hidden="true"><i className="fas fa-check" /></span>
            <h2 id="checkout-title">Payment successful</h2>
            <p className="checkout-paid-amount">{formatPrice(order.price)}</p>
            <dl className="checkout-receipt">
              <dt>Artwork</dt><dd>{order.artworkTitle}</dd>
              <dt>Invoice</dt><dd>{order.invoiceNumber}</dd>
              <dt>Paid by</dt><dd>{paymentMethodLabel(order.paymentMethod)}</dd>
              <dt>Reference</dt><dd><code>{order.paymentId}</code></dd>
            </dl>
            <p className="checkout-note">
              <i className="fas fa-envelope" aria-hidden="true" /> Invoice is on its way to <strong>{order.billingEmail}</strong>.
            </p>
            <div className="d-flex gap-2">
              <Link to={`/orders/${order.id}/invoice`} className="btn btn-primary flex-grow-1" onClick={close}>View invoice</Link>
              <button type="button" className="btn btn-outline-dark" onClick={close}>Done</button>
            </div>
          </div>
        )}

        {(step === "failed" || step === "cancelled") && (
          <div className="checkout-status failed">
            <span className="checkout-cross" aria-hidden="true"><i className="fas fa-xmark" /></span>
            <h2 id="checkout-title">{step === "cancelled" ? "Payment not completed" : "Payment failed"}</h2>
            <p>{step === "cancelled" ? "You closed the payment window before paying." : error}</p>
            <p className="checkout-note">No money has been taken. The artwork stays reserved for you for a short while.</p>
            <div className="d-flex gap-2">
              {checkout?.provider !== "razorpay" && (
                <button type="button" className="btn btn-primary flex-grow-1" onClick={() => { setError(""); setStep("gateway"); }}>Try again</button>
              )}
              <button type="button" className="btn btn-outline-dark" onClick={close}>Close</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Razorpay (real gateway, free test mode)                             */
/* ------------------------------------------------------------------ */

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
async function openRazorpay(checkout) {
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
      prefill: { name: checkout.buyerName, email: checkout.email },
      theme: { color: "#1a1a1a" },
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

/* ------------------------------------------------------------------ */
/* Demo gateway: looks and behaves like an Indian UPI checkout          */
/* ------------------------------------------------------------------ */

const GATEWAY_TABS = [
  { id: "UPI", label: "UPI / QR", icon: "fa-qrcode", hint: "Google Pay, PhonePe, Paytm & more" },
  { id: "CARD", label: "Cards", icon: "fa-credit-card", hint: "Visa, Mastercard, RuPay" },
  { id: "NETBANKING", label: "Netbanking", icon: "fa-building-columns", hint: "All major banks" },
  { id: "WALLET", label: "Wallets", icon: "fa-wallet", hint: "Paytm, PhonePe, Amazon Pay" },
];
const UPI_APPS = ["Google Pay", "PhonePe", "Paytm", "BHIM"];
const BANKS = ["State Bank of India", "HDFC Bank", "ICICI Bank", "Axis Bank", "Kotak Mahindra Bank", "Bank of Baroda"];
const WALLETS = ["Paytm Wallet", "PhonePe Wallet", "Amazon Pay", "MobiKwik"];
const DEMO_OTP = "123456";
const VPA_PATTERN = /^[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z]{2,64}$/;

function luhnValid(number) {
  const digits = number.replace(/\D/g, "");
  if (digits.length < 12 || digits.length > 19) return false;
  let sum = 0;
  digits.split("").reverse().forEach((char, index) => {
    let digit = Number(char);
    if (index % 2 === 1) {
      digit *= 2;
      if (digit > 9) digit -= 9;
    }
    sum += digit;
  });
  return sum % 10 === 0;
}

function DemoGateway({ checkout, onPaid, onFailed }) {
  const [tab, setTab] = useState("UPI");
  // A screen inside the method: "form", or a waiting/approval screen
  const [screen, setScreen] = useState({ name: "form" });
  const now = useNow(1000);
  const amount = formatPrice(checkout.amount / 100);
  const expiresAt = checkout.holdExpiresAt;

  function succeed(method) {
    const random = Math.random().toString(36).slice(2, 12);
    setScreen({ name: "processing" });
    setTimeout(() => onPaid({
      providerOrderId: checkout.providerOrderId,
      paymentId: `mock_pay_${random}`,
      signature: "demo",
      method,
    }), 900);
  }

  function fail(message) {
    setScreen({ name: "processing" });
    setTimeout(() => onFailed(message), 700);
  }

  function switchTab(id) {
    setTab(id);
    setScreen({ name: "form" });
  }

  return (
    <div className="gateway">
      <header className="gateway-header">
        <div>
          <span className="gateway-brand">ArtConnect <strong>Pay</strong></span>
          <span className="gateway-demo">DEMO</span>
        </div>
        <div className="gateway-merchant">
          <span>{checkout.artworkTitle}</span>
          <strong id="checkout-title">{amount}</strong>
        </div>
      </header>
      <p className="gateway-banner">
        <i className="fas fa-flask" aria-hidden="true" /> Test mode: no real money moves. Use the hints below to simulate success or failure.
        {expiresAt && <> Reservation ends in <strong>{timeLeft(expiresAt, now)}</strong>.</>}
      </p>

      <div className="gateway-body">
        <nav className="gateway-tabs" aria-label="Payment method">
          {GATEWAY_TABS.map((item) => (
            <button key={item.id} type="button" className={tab === item.id ? "active" : ""} onClick={() => switchTab(item.id)}
              disabled={screen.name === "processing"}>
              <i className={`fas ${item.icon}`} aria-hidden="true" />
              <span><strong>{item.label}</strong><small>{item.hint}</small></span>
            </button>
          ))}
        </nav>

        <section className="gateway-panel">
          {screen.name === "processing" ? (
            <div className="checkout-status">
              <div className="spinner-border" role="status" aria-hidden="true" />
              <p>Processing payment…</p>
            </div>
          ) : tab === "UPI" ? (
            <UpiPanel checkout={checkout} amount={amount} screen={screen} setScreen={setScreen} succeed={succeed} fail={fail} />
          ) : tab === "CARD" ? (
            <CardPanel amount={amount} screen={screen} setScreen={setScreen} succeed={succeed} fail={fail} />
          ) : tab === "NETBANKING" ? (
            <ChoicePanel kind="NETBANKING" options={BANKS} amount={amount} screen={screen} setScreen={setScreen} succeed={succeed} fail={fail} />
          ) : (
            <ChoicePanel kind="WALLET" options={WALLETS} amount={amount} screen={screen} setScreen={setScreen} succeed={succeed} fail={fail} />
          )}
        </section>
      </div>
      <footer className="gateway-footer">
        <i className="fas fa-shield-halved" aria-hidden="true" /> Secured demo checkout · Invoice to {checkout.email}
      </footer>
    </div>
  );
}

function UpiPanel({ checkout, amount, screen, setScreen, succeed, fail }) {
  const [qr, setQr] = useState("");
  const [vpa, setVpa] = useState("");
  const [error, setError] = useState("");
  const now = useNow(1000);
  const timer = useRef(null);

  useEffect(() => {
    // A demo payload, deliberately NOT a real "upi://pay" link, so scanning it can never charge anyone
    const payload = `ARTCONNECT-DEMO-PAYMENT|${checkout.providerOrderId}|INR ${checkout.amount / 100}`;
    QRCode.toDataURL(payload, { margin: 1, width: 220, color: { dark: "#1a1a1a", light: "#ffffff" } })
      .then(setQr)
      .catch(() => setQr(""));
  }, [checkout.providerOrderId, checkout.amount]);

  useEffect(() => () => clearTimeout(timer.current), []);

  function requestPayment(event) {
    event.preventDefault();
    if (!VPA_PATTERN.test(vpa.trim())) {
      setError("Enter a valid UPI ID, e.g. yourname@okhdfcbank");
      return;
    }
    setError("");
    const id = vpa.trim().toLowerCase();
    setScreen({ name: "collect", vpa: id, expiresAt: Date.now() + 5 * 60 * 1000 });
    // Simulate the customer approving (or declining) in their UPI app a few seconds later
    timer.current = setTimeout(() => {
      if (id.startsWith("failure")) fail("The UPI payment request was declined.");
      else succeed("UPI");
    }, 4500);
  }

  if (screen.name === "collect") {
    return (
      <div className="upi-collect">
        <i className="fas fa-mobile-screen-button upi-phone" aria-hidden="true" />
        <h3>Approve the payment in your UPI app</h3>
        <p>A request for <strong>{amount}</strong> has been sent to <strong>{screen.vpa}</strong>.</p>
        <p className="upi-timer">Request expires in {timeLeft(screen.expiresAt, now)}</p>
        <p className="gateway-hint">Demo: the request is approved automatically in a few seconds.</p>
        <div className="d-flex gap-2 justify-content-center">
          <button type="button" className="btn btn-success btn-sm" onClick={() => { clearTimeout(timer.current); succeed("UPI"); }}>Simulate approve</button>
          <button type="button" className="btn btn-outline-danger btn-sm" onClick={() => { clearTimeout(timer.current); fail("The UPI payment request was declined."); }}>Simulate decline</button>
        </div>
        <button type="button" className="btn btn-link btn-sm mt-2" onClick={() => { clearTimeout(timer.current); setScreen({ name: "form" }); }}>Cancel and choose another way</button>
      </div>
    );
  }

  return (
    <div className="upi-panel">
      <div className="upi-qr">
        <h3>Scan &amp; pay</h3>
        {qr ? <img src={qr} alt="UPI QR code for this demo payment" width="180" height="180" /> : <div className="upi-qr-placeholder" />}
        <p>Scan with any UPI app</p>
        <div className="upi-apps">{UPI_APPS.map((app) => <span key={app}>{app}</span>)}</div>
        <button type="button" className="btn btn-primary btn-sm mt-2" onClick={() => succeed("UPI")}>Simulate scan &amp; pay</button>
      </div>
      <div className="upi-divider"><span>or</span></div>
      <form className="upi-id" onSubmit={requestPayment}>
        <h3>Pay with UPI ID</h3>
        <label className="form-label" htmlFor="upi-vpa">UPI ID</label>
        <input id="upi-vpa" className="form-control" placeholder="yourname@okhdfcbank" value={vpa} autoComplete="off"
          onChange={(event) => setVpa(event.target.value)} />
        {error && <p className="text-danger small mt-1 mb-0" role="alert">{error}</p>}
        <button type="submit" className="btn btn-primary w-100 mt-3">Verify &amp; pay {amount}</button>
        <p className="gateway-hint">Test IDs: <code>success@upi</code> approves, <code>failure@upi</code> declines.</p>
      </form>
    </div>
  );
}

function CardPanel({ amount, screen, setScreen, succeed, fail }) {
  const [card, setCard] = useState({ number: "", expiry: "", cvv: "", name: "" });
  const [otp, setOtp] = useState("");
  const [error, setError] = useState("");

  function update(field, value) {
    if (field === "number") value = value.replace(/\D/g, "").slice(0, 19).replace(/(\d{4})(?=\d)/g, "$1 ");
    if (field === "expiry") value = value.replace(/\D/g, "").slice(0, 4).replace(/^(\d{2})(\d)/, "$1/$2");
    if (field === "cvv") value = value.replace(/\D/g, "").slice(0, 4);
    setCard((current) => ({ ...current, [field]: value }));
  }

  function submitCard(event) {
    event.preventDefault();
    const [month, year] = card.expiry.split("/").map(Number);
    const expiry = new Date(2000 + (year || 0), month || 0, 1);
    if (!luhnValid(card.number)) return setError("Enter a valid card number.");
    if (!month || month > 12 || expiry <= new Date()) return setError("Enter a valid expiry date (MM/YY).");
    if (card.cvv.length < 3) return setError("Enter the 3 or 4 digit CVV.");
    if (!card.name.trim()) return setError("Enter the name on the card.");
    setError("");
    if (card.number.replace(/\s/g, "") === "4000000000000002") {
      fail("Your bank declined this card. Try another card or UPI.");
      return;
    }
    setScreen({ name: "otp" });
  }

  function submitOtp(event) {
    event.preventDefault();
    if (otp !== DEMO_OTP) {
      setError("Incorrect OTP. Please try again.");
      return;
    }
    succeed("CARD");
  }

  if (screen.name === "otp") {
    return (
      <form className="card-otp" onSubmit={submitOtp}>
        <h3>Verify it&rsquo;s you</h3>
        <p>Enter the one-time password sent to the mobile number registered with your card to pay {amount}.</p>
        <input className="form-control otp-input" inputMode="numeric" maxLength={6} autoFocus value={otp} aria-label="One-time password"
          onChange={(event) => setOtp(event.target.value.replace(/\D/g, ""))} />
        {error && <p className="text-danger small mt-1 mb-0" role="alert">{error}</p>}
        <button type="submit" className="btn btn-primary w-100 mt-3">Submit OTP</button>
        <p className="gateway-hint">Demo OTP: <code>{DEMO_OTP}</code></p>
      </form>
    );
  }

  return (
    <form onSubmit={submitCard}>
      <h3>Pay with a debit or credit card</h3>
      <label className="form-label" htmlFor="card-number">Card number</label>
      <input id="card-number" className="form-control mb-2" inputMode="numeric" placeholder="4111 1111 1111 1111" autoComplete="off"
        value={card.number} onChange={(event) => update("number", event.target.value)} />
      <div className="row g-2 mb-2">
        <div className="col-6">
          <label className="form-label" htmlFor="card-expiry">Expiry</label>
          <input id="card-expiry" className="form-control" inputMode="numeric" placeholder="MM/YY" autoComplete="off"
            value={card.expiry} onChange={(event) => update("expiry", event.target.value)} />
        </div>
        <div className="col-6">
          <label className="form-label" htmlFor="card-cvv">CVV</label>
          <input id="card-cvv" className="form-control" inputMode="numeric" placeholder="123" type="password" autoComplete="off"
            value={card.cvv} onChange={(event) => update("cvv", event.target.value)} />
        </div>
      </div>
      <label className="form-label" htmlFor="card-name">Name on card</label>
      <input id="card-name" className="form-control" autoComplete="off" value={card.name}
        onChange={(event) => update("name", event.target.value)} />
      {error && <p className="text-danger small mt-1 mb-0" role="alert">{error}</p>}
      <button type="submit" className="btn btn-primary w-100 mt-3">Pay {amount}</button>
      <p className="gateway-hint">Test cards: <code>4111 1111 1111 1111</code> succeeds, <code>4000 0000 0000 0002</code> is declined. Any future expiry and CVV.</p>
    </form>
  );
}

function ChoicePanel({ kind, options, amount, screen, setScreen, succeed, fail }) {
  const [choice, setChoice] = useState(options[0]);
  const isBank = kind === "NETBANKING";

  if (screen.name === "redirect") {
    return (
      <div className="bank-redirect">
        <p className="bank-redirect-name">{screen.choice}</p>
        <h3>{isBank ? "Net banking login" : "Wallet"} (simulated)</h3>
        <p>Approve a payment of <strong>{amount}</strong> to <strong>ArtConnect</strong>.</p>
        <div className="d-flex gap-2 justify-content-center">
          <button type="button" className="btn btn-success" onClick={() => succeed(kind)}>Approve payment</button>
          <button type="button" className="btn btn-outline-danger" onClick={() => fail(`The payment was rejected at ${screen.choice}.`)}>Reject</button>
        </div>
      </div>
    );
  }

  return (
    <form onSubmit={(event) => { event.preventDefault(); setScreen({ name: "redirect", choice }); }}>
      <h3>{isBank ? "Choose your bank" : "Choose a wallet"}</h3>
      <div className="choice-grid" role="radiogroup">
        {options.map((option) => (
          <label key={option} className={choice === option ? "active" : ""}>
            <input type="radio" name={kind} value={option} checked={choice === option} onChange={() => setChoice(option)} />
            {option}
          </label>
        ))}
      </div>
      <button type="submit" className="btn btn-primary w-100 mt-3">{isBank ? "Continue to bank" : "Pay with wallet"} · {amount}</button>
      <p className="gateway-hint">Demo: you&rsquo;ll get a simulated {isBank ? "bank" : "wallet"} page where you can approve or reject.</p>
    </form>
  );
}
