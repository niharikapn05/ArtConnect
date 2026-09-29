import { Navigate, Route, Routes, useLocation, useParams } from "react-router-dom";
import { assetUrl } from "./api.js";
import { RequireAuth } from "./auth.jsx";
import { Footer, Header } from "./components.jsx";
import { LoginPage, ProfilePage, RegisterPage } from "./pages/AccountPages.jsx";
import { ArtistProfilePage, ArtistsPage } from "./pages/ArtistPages.jsx";
import ArtworkPage from "./pages/ArtworkPage.jsx";
import BrowsePage from "./pages/BrowsePage.jsx";
import CollectionPage from "./pages/CollectionPage.jsx";
import { ExhibitionFormPage, ExhibitionPage, ExhibitionsPage } from "./pages/ExhibitionPages.jsx";
import HomePage from "./pages/HomePage.jsx";
import { AboutPage, ContactPage, NotFoundPage } from "./pages/InfoPages.jsx";
import { ArtworkFormPage, StudioPage } from "./pages/StudioPages.jsx";

function SiteLayout({ children }) {
  const { pathname } = useLocation();
  const backgroundVideo = pathname === "/" ? "bg1.mp4" : "bg2.mp4";

  return (
    <>
      <video key={backgroundVideo} autoPlay muted loop playsInline id="bgVideo" aria-hidden="true">
        <source src={assetUrl(`/videos/${backgroundVideo}`)} type="video/mp4" />
      </video>
      <Header />
      <main>{children}</main>
      <Footer />
    </>
  );
}

function AuthenticationPage({ children }) {
  return (
    <div className="auth-page" style={{ backgroundImage: `url("${assetUrl("/images/bg.jpg")}")` }}>
      {children}
    </div>
  );
}

/** Keeps links to the old /artwork/details/:id URL working. */
function LegacyArtworkRedirect() {
  const { id } = useParams();
  return <Navigate to={`/artworks/${id}`} replace />;
}

const site = (page) => <SiteLayout>{page}</SiteLayout>;
const member = (page, options = {}) => site(<RequireAuth {...options}>{page}</RequireAuth>);

export default function App() {
  return (
    <Routes>
      <Route path="/" element={site(<HomePage />)} />
      <Route path="/gallery" element={site(<BrowsePage key="gallery" />)} />
      <Route path="/auctions" element={site(<BrowsePage key="auctions" auctionsOnly />)} />
      <Route path="/artworks/:id" element={site(<ArtworkPage />)} />
      <Route path="/artists" element={site(<ArtistsPage />)} />
      <Route path="/artists/:id" element={site(<ArtistProfilePage />)} />
      <Route path="/exhibitions" element={site(<ExhibitionsPage />)} />
      <Route path="/exhibitions/new" element={member(<ExhibitionFormPage />, { artist: true })} />
      <Route path="/exhibitions/:id" element={site(<ExhibitionPage />)} />
      <Route path="/exhibitions/:id/edit" element={member(<ExhibitionFormPage />, { artist: true })} />
      <Route path="/studio" element={member(<StudioPage />, { artist: true })} />
      <Route path="/studio/upload" element={member(<ArtworkFormPage key="upload" />, { artist: true })} />
      <Route path="/studio/artworks/:id/edit" element={member(<ArtworkFormPage key="edit" />, { artist: true })} />
      <Route path="/collection" element={member(<CollectionPage />)} />
      <Route path="/profile" element={member(<ProfilePage />)} />
      <Route path="/about" element={site(<AboutPage />)} />
      <Route path="/contact" element={site(<ContactPage />)} />
      <Route path="/login" element={<AuthenticationPage><LoginPage /></AuthenticationPage>} />
      <Route path="/register" element={<AuthenticationPage><RegisterPage /></AuthenticationPage>} />

      {/* Old URLs from the previous version of the site */}
      <Route path="/artwork/details/:id" element={<LegacyArtworkRedirect />} />
      <Route path="/artist-dashboard" element={<Navigate to="/studio" replace />} />
      <Route path="/buyer-dashboard" element={<Navigate to="/collection" replace />} />
      <Route path="/upload-artwork" element={<Navigate to="/studio/upload" replace />} />

      <Route path="*" element={site(<NotFoundPage />)} />
    </Routes>
  );
}
