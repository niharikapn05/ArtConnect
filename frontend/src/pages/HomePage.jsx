import { Link } from "react-router-dom";
import { useApi } from "../api.js";
import { ArtworkGrid, PageState } from "../components.jsx";
import { ExhibitionCard } from "./ExhibitionPages.jsx";

export default function HomePage() {
  const categories = useApi("/api/categories");
  const auctions = useApi("/api/artworks?liveAuctions=true&sort=ending_soon&size=4");
  const latest = useApi("/api/artworks?status=AVAILABLE&sort=newest&size=8");
  const exhibitions = useApi("/api/exhibitions?status=ONGOING");

  return (
    <>
      <section className="hero">
        <div className="hero-content">
          <h1>Discover. Connect. Collect.</h1>
          <p>Buy, bid on and celebrate original artwork from creative artists around the world.</p>
          <div className="d-flex flex-wrap justify-content-center gap-3">
            <Link to="/gallery" className="btn">Explore Gallery</Link>
            <Link to="/auctions" className="btn btn-outline-hero">Live Auctions</Link>
          </div>
        </div>
      </section>

      <section className="container page-section">
        <h2 className="section-title">Browse by category</h2>
        <div className="category-chips">
          {(categories.data ?? []).map((category) => (
            <Link key={category} className="category-chip" to={`/gallery?category=${encodeURIComponent(category)}`}>{category}</Link>
          ))}
        </div>
      </section>

      <HomeSection title="Live auctions" link="/auctions" linkLabel="All auctions" state={auctions}>
        <ArtworkGrid artworks={auctions.data?.items} empty="No auctions are running right now. Check back soon!" />
      </HomeSection>

      <HomeSection title="Current exhibitions" link="/exhibitions" linkLabel="All exhibitions" state={exhibitions}>
        {exhibitions.data?.length
          ? <div className="exhibition-grid">{exhibitions.data.slice(0, 3).map((e) => <ExhibitionCard key={e.id} exhibition={e} />)}</div>
          : <p className="empty-state">No exhibitions are open right now.</p>}
      </HomeSection>

      <HomeSection title="Fresh from the studio" link="/gallery" linkLabel="View gallery" state={latest}>
        <ArtworkGrid artworks={latest.data?.items} empty="No artworks for sale yet. Artists, be the first to upload!" />
      </HomeSection>
    </>
  );
}

function HomeSection({ title, link, linkLabel, state, children }) {
  return (
    <section className="container page-section">
      <div className="section-header">
        <h2 className="section-title">{title}</h2>
        <Link to={link}>{linkLabel} →</Link>
      </div>
      <PageState loading={state.loading} error={state.error}>{children}</PageState>
    </section>
  );
}
