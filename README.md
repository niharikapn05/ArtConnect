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

### Demo mode (default)

With `PAYMENT_PROVIDER=mock` (the default) no gateway is contacted and a clearly labelled demo payment screen stands in for Razorpay, so the whole flow works locally without keys. **Never run production in demo mode.**

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
| POST | `/api/artworks/{id}/purchase` | Start buying a fixed-price artwork: reserves it and returns checkout details |
| POST | `/api/orders/{id}/checkout` | Get checkout details for an unpaid order (e.g. a won auction) |
| POST | `/api/orders/{id}/payment` | Confirm a payment; the server verifies the gateway signature |
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

