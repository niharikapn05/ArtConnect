import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { mediaUrl, request, useApi } from "../api.js";
import { Countdown, EmptyState, PageState } from "../components.jsx";
import { formatDate, formatPrice } from "../format.js";
import { OrderStatus } from "./StudioPages.jsx";

/** A collector's purchases and auction bids. */
export default function CollectionPage() {
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") ?? "orders";
  const orders = useApi("/api/me/orders");
  const bids = useApi("/api/me/bids");
  const [actionError, setActionError] = useState("");

  async function cancel(order) {
    if (!window.confirm(`Cancel your order for "${order.artworkTitle}"? It will go back on sale.`)) return;
    setActionError("");
    try {
      const updated = await request(`/api/orders/${order.id}/cancel`, { method: "POST" });
      orders.setData((current) => current.map((item) => (item.id === updated.id ? updated : item)));
    } catch (requestError) {
      setActionError(requestError.message);
    }
  }

  return (
    <section className="container page-section">
      <h2 className="mb-1">My Collection</h2>
      <p className="lead-muted">Your purchases and auction activity.</p>
      <div className="filter-buttons justify-content-start" role="tablist">
        <button type="button" role="tab" aria-selected={tab === "orders"} className={`btn ${tab === "orders" ? "btn-primary" : "btn-outline-light"}`} onClick={() => setParams({})}>Orders</button>
        <button type="button" role="tab" aria-selected={tab === "bids"} className={`btn ${tab === "bids" ? "btn-primary" : "btn-outline-light"}`} onClick={() => setParams({ tab: "bids" })}>Bids</button>
      </div>
      {actionError && <p className="text-danger" role="alert">{actionError}</p>}

      {tab === "orders" && (
        <PageState loading={orders.loading} error={orders.error}>
          {orders.data?.length ? (
            <div className="order-list">
              {orders.data.map((order) => (
                <article key={order.id} className="order-card glass-panel">
                  <img src={mediaUrl(order.imageUrl)} alt="" />
                  <div className="order-details">
                    <h3>{order.artworkId ? <Link to={`/artworks/${order.artworkId}`}>{order.artworkTitle}</Link> : order.artworkTitle}</h3>
                    <p>by <Link to={`/artists/${order.artistId}`}>{order.artistName}</Link> · {order.source === "AUCTION" ? "Won at auction" : "Purchased"} {formatDate(order.createdAt)}</p>
                    <p className="order-price">{formatPrice(order.price)}</p>
                  </div>
                  <div className="order-actions">
                    <OrderStatus status={order.status} />
                    {order.status === "PENDING" && (
                      <button type="button" className="btn btn-outline-danger btn-sm" onClick={() => cancel(order)}>Cancel</button>
                    )}
                  </div>
                </article>
              ))}
            </div>
          ) : <EmptyState>No orders yet. <Link to="/gallery">Find something you love</Link>.</EmptyState>}
        </PageState>
      )}

      {tab === "bids" && (
        <PageState loading={bids.loading} error={bids.error}>
          {bids.data?.length ? (
            <div className="order-list">
              {bids.data.map(({ artwork, myHighestBid, leading, won }) => (
                <article key={artwork.id} className="order-card glass-panel">
                  <img src={mediaUrl(artwork.imageUrl)} alt="" />
                  <div className="order-details">
                    <h3><Link to={`/artworks/${artwork.id}`}>{artwork.title}</Link></h3>
                    <p>Your highest bid {formatPrice(myHighestBid)} · current {formatPrice(artwork.currentBid)}</p>
                    <p>{artwork.auctionLive ? <>Ends in <Countdown endsAt={artwork.auctionEndsAt} /></> : "Auction ended"}</p>
                  </div>
                  <div className="order-actions">
                    <BidStanding artwork={artwork} leading={leading} won={won} />
                    {artwork.auctionLive && !leading && <Link to={`/artworks/${artwork.id}`} className="btn btn-primary btn-sm">Bid again</Link>}
                  </div>
                </article>
              ))}
            </div>
          ) : <EmptyState>You haven't bid on anything yet. <Link to="/auctions">See live auctions</Link>.</EmptyState>}
        </PageState>
      )}
    </section>
  );
}

function BidStanding({ artwork, leading, won }) {
  if (won) return <span className="badge bg-success">Won</span>;
  if (artwork.auctionLive) {
    return leading ? <span className="badge bg-success">Leading</span> : <span className="badge bg-danger">Outbid</span>;
  }
  return leading ? <span className="badge bg-info text-dark">Settling</span> : <span className="badge bg-secondary">Not won</span>;
}
