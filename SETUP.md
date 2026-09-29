# ArtConnect – First-Time Setup

This guide takes you from a fresh computer to a running ArtConnect with sample data.
It takes about 15–20 minutes, most of which is installing tools.

ArtConnect has three parts, and all three must be running:

| Part | Technology | Address |
|------|------------|---------|
| Database | PostgreSQL | `localhost:5432` |
| Backend (API) | Spring Boot (Java) | http://localhost:8081 |
| Frontend (website) | React (Vite) | http://localhost:5173 ← open this one in your browser |

Commands below are for **Windows (PowerShell)**. macOS/Linux differences are noted where they matter.

---

## 1. Install the tools (one time)

| Tool | Version | Download | Check it works |
|------|---------|----------|----------------|
| Java JDK | 17 or newer | https://adoptium.net | `java -version` |
| Node.js | 20.19+ or 22.12+ (LTS) | https://nodejs.org | `node -v` and `npm -v` |
| PostgreSQL | 14 or newer | https://www.postgresql.org/download | see step 2 |
| Git | any | https://git-scm.com | `git --version` |

You do **not** need to install Maven. The project includes a Maven wrapper (`mvnw.cmd` / `mvnw`) that downloads it automatically.

**PostgreSQL install tips (Windows):**
- Keep the default port **5432**.
- The installer asks for a password for the `postgres` user. **Write it down**; you need it in step 4.
- You can untick *Stack Builder* at the end.

Open a **new** terminal after installing, so the new commands are found.

---

## 2. Get the code

    git clone <repository-url>
    cd ArtConnect

(If you received the project as a zip, unzip it and open a terminal in the `ArtConnect` folder.)

---

## 3. Create the database

Create an empty database named `artconnect`:

    psql -U postgres -c "CREATE DATABASE artconnect"

Enter the `postgres` password from step 1 when asked.

> **`psql` is not recognized?** Use the full path instead (change `18` to your version):
>
>     & "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -c "CREATE DATABASE artconnect"
>
> Or use **pgAdmin** (installed with PostgreSQL): right-click *Databases* → *Create* → *Database…* → name it `artconnect` → *Save*.

You only create the empty database. **Tables and sample data are created automatically** the first time the backend starts.

---

## 4. Configure your settings (`.env`)

Secrets are not stored in the code. Copy the example file and fill it in:

    Copy-Item .env.example .env

(macOS/Linux: `cp .env.example .env`)

Open `.env` in a text editor and set at least:

    DB_URL=jdbc:postgresql://localhost:5432/artconnect
    DB_USERNAME=postgres
    DB_PASSWORD=your-postgres-password

**Cloudinary (image uploads):** artwork and profile-photo uploads are stored on [Cloudinary](https://cloudinary.com).
Create a free account, open the *Dashboard*, and copy the three values into `.env`:

    CLOUDINARY_CLOUD_NAME=...
    CLOUDINARY_API_KEY=...
    CLOUDINARY_API_SECRET=...

Everything else (browsing, buying, bidding, reviews, and the sample data) works without Cloudinary; only *uploading new images* needs it.

> `.env` is ignored by git. Never commit it or share it publicly.

---

## 5. Start the backend

In a terminal, from the `ArtConnect` folder:

    .\mvnw.cmd spring-boot:run

(macOS/Linux: `./mvnw spring-boot:run`)

The first run downloads dependencies and takes a few minutes. It is ready when you see:

    Started ArtConnectApplication in ... seconds

On this first start it also:
- creates all the database tables, and
- loads the sample data (artists, artworks, auctions, reviews, exhibitions).

**Leave this terminal open**; closing it stops the backend.

Quick check: open http://localhost:8081/api/categories. You should see a list of categories.

---

## 6. Start the frontend

Open a **second** terminal:

    cd frontend
    npm install
    npm run dev

`npm install` is only needed the first time (and after dependencies change).
It is ready when you see `Local: http://localhost:5173/`.

**Leave this terminal open too.**

---

## 7. Open the app

Go to **http://localhost:5173** and log in with a demo account. **Every demo password is `password123`**.

| Username | Role | Try this |
|----------|------|----------|
| `meera_kapoor` | Artist | *Studio*: upload artwork, manage sales, create an exhibition |
| `arjun_rao`, `lena_fischer`, `kofi_mensah` | Artist | Same as above, different portfolios |
| `priya_sharma` | Collector | Buy artwork, bid in *Auctions*, write reviews, *My Collection* |
| `daniel_lee`, `aisha_khan` | Collector | Outbid each other on the live auctions |
| `admin` | Admin | Can edit or delete any artwork, review or exhibition |

Or click **Join** to register your own artist or collector account.

---

## Everyday use (after the first setup)

You only repeat steps 5 and 6. Make sure PostgreSQL is running, then:

| Terminal 1 (project folder) | Terminal 2 (`frontend` folder) |
|-----------------------------|--------------------------------|
| `.\mvnw.cmd spring-boot:run` | `npm run dev` |

Open http://localhost:5173. To stop a server, press **Ctrl + C** in its terminal.

---

## Useful commands

| Task | Command |
|------|---------|
| Run the backend tests (no database needed) | `.\mvnw.cmd test` |
| Build the frontend for production | `cd frontend` then `npm run build` (only builds files into `frontend/dist`; it does **not** start anything) |
| Reset the database to fresh sample data | Stop the backend, then `psql -U postgres -c "DROP DATABASE artconnect" -c "CREATE DATABASE artconnect"`, then start the backend again |
| Start **without** sample data | Add `FLYWAY_LOCATIONS=classpath:db/migration` to `.env` **before** the first start (use this in production) |

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `password authentication failed for user "postgres"` | `DB_PASSWORD` in `.env` is wrong. |
| `database "artconnect" does not exist` | You skipped step 3. |
| `Connection to localhost:5432 refused` | PostgreSQL isn't running. Windows: press Win+R → `services.msc` → start **postgresql-x64-NN**. macOS (Homebrew): `brew services start postgresql`. |
| `Port 8081 was already in use` | Another backend is still running. Close its terminal, or find it with `netstat -ano \| findstr :8081` and stop it with `taskkill /PID <id> /F`. |
| `JAVA_HOME is not set` / `java` not found | Install the JDK (step 1) and open a new terminal. |
| Website loads but shows errors / no artworks | The backend isn't running, or not on port 8081. Start it (step 5) and refresh. |
| `npm install` fails | Check `node -v` meets the version in step 1, then delete `frontend/node_modules` and retry. |
| Image upload says it failed | Check the three `CLOUDINARY_*` values in `.env`, then restart the backend. |
| Changed `.env` but nothing happened | `.env` is read at startup. Restart the backend (Ctrl + C, then step 5 again). |

---

## Where things live

| Path | What it is |
|------|-----------|
| `frontend/` | React website (pages, components, styles) |
| `src/main/java/…` | Spring Boot API (controllers, services, entities) |
| `src/main/resources/db/migration/` | Database schema (SQL, run automatically by Flyway) |
| `src/main/resources/db/sample/` | Sample data (SQL, run automatically on a fresh database) |
| `src/main/resources/static/images/samples/` | Images for the sample artworks |
| `.env` | Your local settings and secrets (not in git) |

See [README.md](README.md) for features, the REST API reference, and deployment.
