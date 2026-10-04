# 🎨 ArtConnect

ArtConnect is an **online art gallery** built with **React**, **Spring Boot**, **PostgreSQL** and **Cloudinary**.
Artists showcase and sell their work, and art lovers browse by category, buy pieces outright or bid on them at auction, review what they love, and visit curated online exhibitions.

---

## 🚀 Features

- 🔑 **Accounts & roles** – Register as an **artist** or a **collector** (collectors can upgrade to artist from their profile). Passwords are BCrypt-hashed; sessions use bearer tokens.
- 🧑‍🎨 **Artist profiles** – Public profile with photo, bio, location, website, all artworks, exhibitions and average rating.
- 🖼️ **Upload artwork** – Title, description, category, tags, medium, dimensions, year and price, with the image stored in Cloudinary.
- 🗂️ **Browse & search** – Filter by category, sale type and availability; search by title, description or artist; sort by newest, price, rating or ending soon; paginated.
- 🛒 **Purchasing** – Fixed-price artworks can be bought once (row-level locking prevents double sales). Buy now reserves the artwork for 30 minutes while the buyer pays; unpaid reservations are released automatically.
- 💳 **Online payments (Razorpay)** – UPI, cards, netbanking and wallets through Razorpay Checkout. Every payment is verified server-side with Razorpay's signature, and a webhook records payments even if the buyer closes the tab. Auction winners pay the same way from *My Collection*. A demo mode (default) lets you try the flow locally without keys or real money.
- 🔨 **Auctions** – Timed auctions with a minimum 5% bid increment, bid history, live countdowns and anti-sniping (late bids extend the auction by 2 minutes). Finished auctions are settled automatically: the winner gets an order, or an unsold piece becomes a fixed-price listing.
- ⭐ **Reviews** – 1–5 star reviews with comments (one per user per artwork; artists can't review their own work), rolled up into artwork and artist ratings.
- 🏛️ **Exhibitions** – Artists curate ordered exhibitions of their work with opening and closing dates (upcoming / now showing / past).
- 📊 **Dashboards** – The artist **Studio** (listings, sales, exhibitions, stats) and the collector's **My Collection** (orders and bids: leading, outbid, won).

---

## 🏗️ Tech Stack

| Layer | Technology |
|-------|-------------|
| Frontend | React 19, React Router, Bootstrap 5, Vite |
| Backend | Spring Boot 3.5 (Java 17), Spring Web, Spring Data JPA, Bean Validation |
| Database | PostgreSQL, with Flyway schema migrations |
| Cloud Storage | Cloudinary |
| Tests | JUnit 5, MockMvc, H2 (PostgreSQL mode) |
| Build | Maven, npm |
| Deployment | Docker / Render |

---

## ⚙️ Project Setup (Local)

> **First time setting up?** Follow the step-by-step guide in **[SETUP.md](SETUP.md)**: installing the tools, creating the database, configuring `.env`, starting the backend and frontend, demo logins and troubleshooting.

### Prerequisites

- Java 17+, Node.js 20+, and PostgreSQL 14+ running locally

### 1. Create the database

    psql -U postgres -c "CREATE DATABASE artconnect"

Tables are created automatically by Flyway on first start (`src/main/resources/db/migration`).

#### Sample data

A fresh database is also filled with demo content from the SQL files in `src/main/resources/db/sample/`: 4 demo sellers, 3 collectors and an admin, 14 artworks (paintings, drawing, photography, prints, sculpture and textile), live auctions with bids, a finished auction awaiting payment, orders, reviews and 4 exhibitions (upcoming, ongoing and past). Dates are relative to the day the database is created, so the auctions are live and the exhibitions are current.

The sample listings use photographs of famous **public-domain / CC0 artworks** (Monet, Van Gogh, Seurat, Hokusai, Rodin and others, from Wikimedia Commons) as placeholder images, each credited in its description. See [`static/images/samples/CREDITS.md`](src/main/resources/static/images/samples/CREDITS.md) for every source and licence.

Every demo account uses the password **`password123`**:

| Username | Role |
|----------|------|
| `meera_kapoor`, `arjun_rao`, `lena_fischer`, `kofi_mensah` | Artist |
| `priya_sharma`, `daniel_lee`, `aisha_khan` | Collector |
| `admin` | Admin |

To start without the demo content (always do this in production), set `FLYWAY_LOCATIONS=classpath:db/migration` before the first start. To reload it into an existing local database, drop and recreate the database:

    psql -U postgres -c "DROP DATABASE artconnect" -c "CREATE DATABASE artconnect"

### 2. Configure secrets

Copy `.env.example` to `.env` in the repository root and fill in your PostgreSQL and Cloudinary credentials. `.env` is git-ignored; on a server, set the same names as environment variables instead.

### 3. Run the application

Start the Spring Boot API from the repository root:

    .\mvnw.cmd spring-boot:run

The API listens on `http://localhost:8081`. In a second terminal, start the React client:

    cd frontend
    npm install
    npm run dev

Open `http://localhost:5173`. Vite proxies `/api`, `/images`, and `/videos` to the local Spring server.

### Run the tests

    .\mvnw.cmd test

The tests run against an in-memory H2 database in PostgreSQL mode (no local database needed) with Cloudinary mocked.

### Deploy

`docker build -t artconnect .` builds the React app and bundles it into the Spring Boot jar, so one container serves both the site and the API. Set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, the `CLOUDINARY_*` variables, the Razorpay variables (see [Payments](#-payments)) and `FLYWAY_LOCATIONS=classpath:db/migration` (so the demo accounts are not created) on the host. To host the frontend separately instead, set `VITE_API_BASE_URL` when building it and `FRONTEND_ORIGIN` on the API.

Admin accounts can't be created through the API: promote a user with `UPDATE app_users SET role = 'ADMIN' WHERE username = '...'`.

---

## 💳 Payments

Purchases go through [Razorpay](https://razorpay.com):

1. **Buy now** reserves the artwork (30 minutes) and creates an order awaiting payment, plus a matching Razorpay order.
2. The browser opens Razorpay Checkout (UPI, cards, netbanking, wallets).
3. On success the backend verifies Razorpay's HMAC-SHA256 signature before marking the order **Paid** and the artwork **Sold**. A forged "payment succeeded" from the browser is rejected.
4. The Razorpay webhook marks the order paid even if the buyer closes the page before step 3.
5. If the buyer doesn't pay in time, the reservation is released and the artwork goes back on sale.

Order statuses: **Awaiting payment → Paid → Delivered** (the artist marks it delivered), or **Cancelled** before payment.
Auction winners get an order awaiting payment and pay from *My Collection*.

### Demo mode (default): "ArtConnect Pay"

With `PAYMENT_PROVIDER=mock` (the default) no gateway is contacted. Instead a built-in **demo gateway that looks like an Indian UPI checkout** handles the payment, so the whole flow works locally without any account or keys. No real money moves, and it's clearly labelled DEMO. **Never run production in demo mode.**

Checkout first asks for the buyer's **email and mobile number** (the invoice goes there), then offers:

| Method | How to simulate it |
|--------|--------------------|
| **UPI QR** | A QR code with a countdown and app chips (Google Pay, PhonePe, Paytm, BHIM). Click *Simulate scan & pay*. The QR is deliberately not a real `upi://` link, so scanning it can never charge anyone. |
| **UPI ID** | Enter any UPI ID, e.g. `success@upi`, and approve the "collect request" (auto-approves after a few seconds). `failure@upi` gets declined. |
| **Cards** | `4111 1111 1111 1111` succeeds, `4000 0000 0000 0002` is declined; any future expiry and CVV; OTP `123456`. |
| **Netbanking / Wallets** | Pick a bank or wallet, then approve or reject on the simulated bank page. |

### Using Razorpay

1. Create a free account at https://dashboard.razorpay.com and stay in **Test Mode**.
2. *Account & Settings → API Keys → Generate Test Key*, then add to `.env`:

       PAYMENT_PROVIDER=razorpay
       RAZORPAY_KEY_ID=rzp_test_xxxxxxxx
       RAZORPAY_KEY_SECRET=xxxxxxxxxxxxxxxx

3. Restart the backend. Pay with Razorpay's [test cards or UPI IDs](https://razorpay.com/docs/payments/payments/test-card-upi-details/) (for example UPI `success@razorpay`); no real money moves with test keys.
4. *(Recommended; required in production)* Add a webhook under *Account & Settings → Webhooks*: URL `https://your-domain/api/payments/razorpay/webhook`, events `payment.captured` and `order.paid`, and a secret, which you set as `RAZORPAY_WEBHOOK_SECRET`. Webhooks need a public URL; locally you can use a tunnel such as ngrok.
5. Keep **automatic capture** enabled in Razorpay's payment settings (the default).

To go live, complete Razorpay's KYC activation and swap in the `rzp_live_...` keys.

Paid orders can't be cancelled in the app. Refunds are issued from the Razorpay dashboard. If a payment ever arrives after its reservation expired and the artwork was sold to someone else, the server logs `REFUND NEEDED` with the payment id.

---

## 🧾 Invoices by email and SMS

When a payment is verified, the order gets an invoice number (`INV-2026-000042`) and the invoice is sent to the email and mobile number entered at checkout, in the background, so it never slows down or fails the payment. Buyers and sellers can open the invoice in the app (*My Collection* / *Studio → Sales* → **Invoice**), print it or save it as PDF, see where it was delivered, and resend it.

Out of the box nothing is really sent: each email is saved as an `.html` file in the `outbox/` folder (open it in a browser) and each SMS is appended to `outbox/sms.log`. Delivery shows as *Demo outbox* on the invoice page. To send for real, set these in `.env` and restart the backend:

**Email (any SMTP server).** Free options: Gmail with an [app password](https://support.google.com/accounts/answer/185833), [Brevo](https://www.brevo.com) (300 emails/day free), or a [Mailtrap](https://mailtrap.io) test inbox that catches every email without delivering it:

    EMAIL_PROVIDER=smtp
    SMTP_HOST=smtp.gmail.com
    SMTP_PORT=587
    SMTP_USERNAME=you@gmail.com
    SMTP_PASSWORD=your-16-character-app-password
    EMAIL_FROM=ArtConnect <you@gmail.com>

**SMS ([Twilio](https://www.twilio.com)).** The text is a short order confirmation ("ArtConnect: Order #41 confirmed. Payment of Rs.2,500.00 received; your invoice has been emailed to you."); the full invoice goes by email.

*Free trial account:* Twilio trial accounts [can't send custom text](https://www.twilio.com/docs/usage/trials/try-out-sms), only Twilio's predefined templates, and only to numbers verified in the Twilio console. They must also send from the **trial number Twilio assigned to your verified recipient** (shown in the Twilio console), otherwise Twilio returns error 572003:

    SMS_PROVIDER=twilio
    TWILIO_ACCOUNT_SID=ACxxxxxxxx
    TWILIO_AUTH_TOKEN=xxxxxxxx
    TWILIO_FROM_NUMBER=+1xxxxxxxxxx   (your assigned trial number)
    TWILIO_TRIAL_TEMPLATE=sms_order_confirmation

*Upgraded account:* remove `TWILIO_TRIAL_TEMPLATE` and set `TWILIO_FROM_NUMBER=+1xxxxxxxxxx` to send ArtConnect's own text to any number.

Set `PUBLIC_URL` to your website's address so the "view invoice" links in emails and texts point to it (default `http://localhost:5173`).

---

## 🔌 REST API

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/auth/register`, `/api/auth/login`, `/api/auth/logout` | Accounts and sessions |
| GET / PUT | `/api/me` | Current user / update profile (incl. upgrading to artist) |
| POST | `/api/me/avatar` | Upload profile photo |
| GET | `/api/me/artworks`, `/api/me/orders`, `/api/me/sales`, `/api/me/bids` | Dashboard data |
| GET | `/api/categories` | Artwork categories |
| GET | `/api/artworks?q=&category=&tag=&saleType=&status=&artistId=&minPrice=&maxPrice=&liveAuctions=&sort=&page=&size=` | Browse |
| GET / POST / PUT / DELETE | `/api/artworks`, `/api/artworks/{id}` | Artwork CRUD (upload is multipart) |
| POST | `/api/artworks/{id}/purchase` | Start buying a fixed-price artwork (body: `email`, `phone`): reserves it and returns checkout details |
| POST | `/api/orders/{id}/checkout` | Get checkout details for an unpaid order (e.g. a won auction) |
| POST | `/api/orders/{id}/payment` | Confirm a payment; the server verifies the gateway signature and issues the invoice |
| GET / POST | `/api/orders/{id}/invoice`, `/api/orders/{id}/invoice/send` | View an invoice (buyer, seller, admin) / resend it (buyer) |
| POST | `/api/payments/razorpay/webhook` | Razorpay server-to-server payment notifications |
| GET / POST | `/api/artworks/{id}/bids` | Bid history / place a bid |
| GET / POST | `/api/artworks/{id}/reviews`, DELETE `/api/reviews/{id}` | Reviews |
| POST | `/api/orders/{id}/cancel`, `/api/orders/{id}/complete` | Order lifecycle |
| GET | `/api/artists`, `/api/artists/{id}` | Artist directory and profiles |
| GET / POST / PUT / DELETE | `/api/exhibitions`, `/api/exhibitions/{id}` | Exhibitions |

Errors are returned as `{"message": "..."}` with an appropriate HTTP status.

---

## 🗂️ Project Structure

    ArtConnect/
    ├── frontend/                       # React + Vite single-page app
    │   └── src/
    │       ├── api.js, auth.jsx        # API client, session handling
    │       ├── components.jsx          # Shared UI (header, artwork cards, ratings, ...)
    │       └── pages/                  # Gallery, auctions, artwork, artists, exhibitions, studio, ...
    ├── src/main/java/com/example/ArtConnect/
    │   ├── controller/                 # REST endpoints
    │   ├── service/                    # Business rules (orders, auctions, reviews, ...)
    │   ├── repository/                 # Spring Data JPA repositories
    │   ├── model/                      # JPA entities
    │   ├── dto/                        # Request/response records
    │   ├── security/                   # Bearer-token @CurrentUser resolution
    │   └── config/, exception/
    ├── src/main/resources/
    │   ├── db/migration/               # Flyway SQL migrations
    │   ├── static/                     # Images and videos (plus the React build in Docker)
    │   └── application.properties
    ├── Dockerfile
    └── pom.xml

