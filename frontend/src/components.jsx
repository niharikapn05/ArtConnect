import { Link, NavLink, useNavigate } from "react-router-dom";
import { mediaUrl } from "./api.js";
import { useAuth } from "./auth.jsx";
import { formatPrice, timeLeft, useNow } from "./format.js";

export function Header() {
  const { user, isArtist, logout } = useAuth();
  const navigate = useNavigate();

  async function handleLogout() {
    await logout();
    navigate("/");
  }

  return (
    <header>
      <nav className="navbar navbar-expand-lg navbar-dark">
        <div className="container-fluid">
          <Link className="navbar-brand" to="/">ArtConnect</Link>
          <button
            className="navbar-toggler"
            type="button"
            data-bs-toggle="collapse"
            data-bs-target="#navbarNav"
            aria-controls="navbarNav"
            aria-expanded="false"
            aria-label="Toggle navigation"
          >
            <span className="navbar-toggler-icon" />
          </button>
          <div className="collapse navbar-collapse justify-content-end" id="navbarNav">
            <ul className="navbar-nav align-items-lg-center">
              <li className="nav-item"><NavLink className="nav-link" to="/gallery">Gallery</NavLink></li>
              <li className="nav-item"><NavLink className="nav-link" to="/auctions">Auctions</NavLink></li>
              <li className="nav-item"><NavLink className="nav-link" to="/artists">Artists</NavLink></li>
              <li className="nav-item"><NavLink className="nav-link" to="/exhibitions">Exhibitions</NavLink></li>
              <li className="nav-item"><NavLink className="nav-link" to="/about">About</NavLink></li>
              {user ? (
                <>
                  {isArtist && <li className="nav-item"><NavLink className="nav-link" to="/studio">Studio</NavLink></li>}
                  <li className="nav-item"><NavLink className="nav-link" to="/collection">My Collection</NavLink></li>
                  <li className="nav-item">
                    <NavLink className="nav-link nav-user" to="/profile" title="Edit profile">
                      <Avatar src={user.avatarUrl} name={user.displayName} size={28} />
                      <span>{user.displayName}</span>
                    </NavLink>
                  </li>
                  <li className="nav-item">
                    <button type="button" className="btn btn-outline-light btn-sm ms-lg-2" onClick={handleLogout}>Log out</button>
                  </li>
                </>
              ) : (
                <>
                  <li className="nav-item"><NavLink className="nav-link" to="/login">Log in</NavLink></li>
                  <li className="nav-item"><Link className="btn btn-primary btn-sm ms-lg-2" to="/register">Join</Link></li>
                </>
              )}
            </ul>
          </div>
        </div>
      </nav>
    </header>
  );
}

export function Footer() {
  return (
    <footer className="site-footer">
      <div className="container text-center">
        <p>
          &copy; {new Date().getFullYear()} ArtConnect. Created by <strong>Chandana</strong>.
          {" "}Crafted with &hearts; and code. All rights reserved.
        </p>
      </div>
    </footer>
  );
}

export function PageState({ loading, error, children }) {
  if (loading) return <p className="page-state" role="status">Loading...</p>;
  if (error) return <p className="page-state text-danger" role="alert">{error}</p>;
  return children;
}

export function EmptyState({ children }) {
  return <p className="empty-state">{children}</p>;
}

export function Avatar({ src, name = "", size = 40 }) {
  const initials = name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0].toUpperCase()).join("");
  return src
    ? <img className="avatar" src={mediaUrl(src)} alt="" width={size} height={size} style={{ width: size, height: size }} />
    : <span className="avatar avatar-fallback" style={{ width: size, height: size, fontSize: size * 0.4 }} aria-hidden="true">{initials || "?"}</span>;
}

export function Stars({ value, count }) {
  const rounded = Math.round(value);
  return (
    <span className="stars" aria-label={`Rated ${value} out of 5${count != null ? ` from ${count} reviews` : ""}`}>
      <span aria-hidden="true">{"★".repeat(rounded)}<span className="stars-empty">{"★".repeat(5 - rounded)}</span></span>
      {count != null && <span className="stars-count">({count})</span>}
    </span>
  );
}

export function Countdown({ endsAt }) {
  const now = useNow(1000);
  return <span className="countdown">{timeLeft(endsAt, now)}</span>;
}

export function SaleBadge({ artwork }) {
  if (artwork.status === "SOLD") return <span className="badge bg-secondary">Sold</span>;
  if (artwork.saleType === "AUCTION") {
    return artwork.auctionLive
      ? <span className="badge bg-warning text-dark">Live auction</span>
      : <span className="badge bg-secondary">Auction ended</span>;
  }
  return <span className="badge bg-success">Buy now</span>;
}

export function ArtworkCard({ artwork, children }) {
  const isAuction = artwork.saleType === "AUCTION";
  return (
    <article className="art-card">
      <Link to={`/artworks/${artwork.id}`} className="artwork-link">
        <div className="art-card-image">
          <img src={mediaUrl(artwork.imageUrl)} alt={artwork.title} loading="lazy" />
          <div className="art-card-badge"><SaleBadge artwork={artwork} /></div>
        </div>
        <div className="card-body">
          <h3>{artwork.title}</h3>
          <p className="art-card-artist">by {artwork.artistName} · {artwork.category}</p>
          <div className="art-card-price">
            {isAuction && artwork.status !== "SOLD" ? (
              <>
                <span>
                  <small>{artwork.currentBid != null ? "Current bid" : "Starting bid"}</small>
                  {formatPrice(artwork.currentBid ?? artwork.price)}
                </span>
                {artwork.auctionLive && <span className="text-end"><small>Ends in</small><Countdown endsAt={artwork.auctionEndsAt} /></span>}
              </>
            ) : (
              <span><small>{artwork.status === "SOLD" ? "Sold for" : "Price"}</small>{formatPrice(artwork.price)}</span>
            )}
          </div>
          {artwork.reviewCount > 0 && <Stars value={artwork.averageRating} count={artwork.reviewCount} />}
        </div>
      </Link>
      {children && <div className="card-actions">{children}</div>}
    </article>
  );
}

export function ArtworkGrid({ artworks, empty = "No artworks to show yet.", actions }) {
  if (!artworks?.length) return <EmptyState>{empty}</EmptyState>;
  return (
    <div className="gallery-container">
      {artworks.map((artwork) => (
        <ArtworkCard key={artwork.id} artwork={artwork}>{actions?.(artwork)}</ArtworkCard>
      ))}
    </div>
  );
}

export function Pagination({ page, totalPages, onChange }) {
  if (totalPages <= 1) return null;
  return (
    <nav className="pagination-bar" aria-label="Pages">
      <button type="button" className="btn btn-outline-light btn-sm" disabled={page === 0} onClick={() => onChange(page - 1)}>Previous</button>
      <span>Page {page + 1} of {totalPages}</span>
      <button type="button" className="btn btn-outline-light btn-sm" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>Next</button>
    </nav>
  );
}
