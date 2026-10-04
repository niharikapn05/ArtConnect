package com.example.ArtConnect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ArtConnect.repository.ArtworkRepository;
import com.example.ArtConnect.service.AuctionService;
import com.example.ArtConnect.service.CloudinaryService;
import com.example.ArtConnect.service.PaymentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

/** End-to-end API tests against the real schema (H2 in PostgreSQL mode), with Cloudinary mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GalleryApiTests {
    private static final AtomicInteger USER_COUNTER = new AtomicInteger();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ArtworkRepository artworkRepository;
    @Autowired AuctionService auctionService;
    @Autowired PaymentService paymentService;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate transactionTemplate;
    @MockitoBean CloudinaryService cloudinaryService;

    @BeforeEach
    void stubImageUploads() {
        when(cloudinaryService.uploadImage(any(), anyString())).thenReturn("https://img.example/art.jpg");
    }

    // ---------- Accounts ----------

    @Test
    void registerLoginAndProtectedEndpoints() throws Exception {
        String username = "Painter" + USER_COUNTER.incrementAndGet();
        mvc.perform(postJson("/api/auth/register",
                        Map.of("username", username, "password", "secret1", "role", "ARTIST")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(username.toLowerCase()))
                .andExpect(jsonPath("$.user.role").value("ARTIST"));

        // Usernames are case-insensitive
        mvc.perform(postJson("/api/auth/register",
                        Map.of("username", username.toUpperCase(), "password", "secret1", "role", "COLLECTOR")))
                .andExpect(status().isConflict());
        mvc.perform(postJson("/api/auth/login", Map.of("username", username, "password", "wrong-pw")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));

        String token = body(mvc.perform(postJson("/api/auth/login", Map.of("username", username, "password", "secret1")))
                .andExpect(status().isOk())).get("token").asText();
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ARTIST"));

        mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsAllowsLocalDevOriginsOnAnyPortButNotOtherSites() throws Exception {
        String username = "corsuser" + USER_COUNTER.incrementAndGet();
        mvc.perform(postJson("/api/auth/register", Map.of("username", username, "password", "secret1", "role", "COLLECTOR")))
                .andExpect(status().isCreated());
        for (String origin : new String[] { "http://localhost:5173", "http://localhost:5174", "http://127.0.0.1:5173" }) {
            mvc.perform(postJson("/api/auth/login", Map.of("username", username, "password", "secret1"))
                            .header(HttpHeaders.ORIGIN, origin))
                    .andExpect(status().isOk());
        }
        mvc.perform(postJson("/api/auth/login", Map.of("username", username, "password", "secret1"))
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    void registrationValidatesInputAndRejectsAdminRole() throws Exception {
        mvc.perform(postJson("/api/auth/register", Map.of("username", "ab", "password", "secret1", "role", "ARTIST")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username size must be between 3 and 30"));
        mvc.perform(postJson("/api/auth/register", Map.of("username", "sneaky", "password", "secret1", "role", "ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void collectorCanBecomeArtistFromProfile() throws Exception {
        String collector = register("COLLECTOR");
        mvc.perform(uploadArtwork(collector, "Nope", "FIXED", null)).andExpect(status().isForbidden());

        mvc.perform(authed(put("/api/me"), collector).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("displayName", "New Artist", "bio", "Hello",
                                "website", "https://example.com", "becomeArtist", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ARTIST"))
                .andExpect(jsonPath("$.displayName").value("New Artist"));
        mvc.perform(uploadArtwork(collector, "Now allowed", "FIXED", null)).andExpect(status().isCreated());
    }

    // ---------- Artworks & browsing ----------

    @Test
    void uploadBrowseEditAndDeleteArtwork() throws Exception {
        String artist = register("ARTIST");
        String other = register("ARTIST");
        long id = createArtwork(artist, "Moonlit Harbour Zqx", "FIXED", null);

        mvc.perform(get("/api/artworks").param("q", "harbour zqx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Moonlit Harbour Zqx"))
                .andExpect(jsonPath("$.items[0].tags[0]").value("sea"))
                .andExpect(jsonPath("$.items[0].status").value("AVAILABLE"));
        mvc.perform(get("/api/artworks").param("q", "harbour zqx").param("category", "Sculpture"))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/artworks").param("q", "harbour zqx").param("tag", "SEA"))
                .andExpect(jsonPath("$.totalItems").value(1));
        // LIKE wildcards in the search text are treated literally
        mvc.perform(get("/api/artworks").param("q", "%")).andExpect(jsonPath("$.totalItems").value(0));

        Map<String, Object> edit = Map.of("title", "Moonlit Harbour II", "description", "Revised",
                "price", 2500, "category", "Painting", "tags", "sea, night");
        mvc.perform(authed(put("/api/artworks/" + id), other).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(edit)))
                .andExpect(status().isForbidden());
        mvc.perform(authed(put("/api/artworks/" + id), artist).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(edit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Moonlit Harbour II"))
                .andExpect(jsonPath("$.tags.length()").value(2));

        mvc.perform(authed(delete("/api/artworks/" + id), other)).andExpect(status().isForbidden());
        mvc.perform(authed(delete("/api/artworks/" + id), artist)).andExpect(status().isNoContent());
        mvc.perform(get("/api/artworks/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void uploadRejectsInvalidInput() throws Exception {
        String artist = register("ARTIST");
        mvc.perform(authed(multipart("/api/artworks").file(image())
                        .param("title", "No price").param("description", "d").param("category", "Painting"), artist))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Price must not be null"));
        mvc.perform(authed(multipart("/api/artworks").file(image()).param("title", "Bad category")
                        .param("description", "d").param("price", "10").param("category", "Crayons"), artist))
                .andExpect(status().isBadRequest());
        mvc.perform(authed(multipart("/api/artworks").file(image()).param("title", "Auction without duration")
                        .param("description", "d").param("price", "10").param("category", "Painting")
                        .param("saleType", "AUCTION"), artist))
                .andExpect(status().isBadRequest());
        mvc.perform(uploadArtwork(null, "Anonymous", "FIXED", null)).andExpect(status().isUnauthorized());
    }

    // ---------- Purchasing ----------

    @Test
    void checkoutReservesArtworkAndVerifiedPaymentSellsIt() throws Exception {
        String artist = register("ARTIST");
        String buyer = register("COLLECTOR");
        String otherBuyer = register("COLLECTOR");
        long id = createArtwork(artist, "One of a kind", "FIXED", null);

        mvc.perform(buy(artist, id)).andExpect(status().isBadRequest());
        JsonNode checkout = body(mvc.perform(buy(buyer, id))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("mock"))
                .andExpect(jsonPath("$.amount").value(150000)) // paise
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.holdExpiresAt").isNotEmpty()));
        long orderId = checkout.get("orderId").asLong();
        String providerOrderId = checkout.get("providerOrderId").asText();

        // Reserved while the buyer pays; others are turned away, the buyer can resume
        mvc.perform(get("/api/artworks/" + id)).andExpect(jsonPath("$.status").value("RESERVED"));
        mvc.perform(buy(otherBuyer, id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Another buyer is completing payment for this artwork. Please check back soon."));
        mvc.perform(buy(buyer, id))
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.providerOrderId").value(providerOrderId));
        mvc.perform(authed(get("/api/me/orders"), buyer)).andExpect(jsonPath("$[0].status").value("AWAITING_PAYMENT"));
        mvc.perform(authed(post("/api/orders/" + orderId + "/complete"), artist)).andExpect(status().isConflict());

        // Payments that don't match or can't be verified are rejected
        mvc.perform(pay(buyer, orderId, "mock_order_someone_else", "mock_pay_1")).andExpect(status().isBadRequest());
        mvc.perform(pay(buyer, orderId, providerOrderId, "forged_payment")).andExpect(status().isBadRequest());
        mvc.perform(pay(otherBuyer, orderId, providerOrderId, "mock_pay_1")).andExpect(status().isForbidden());

        mvc.perform(pay(buyer, orderId, providerOrderId, "mock_pay_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentId").value("mock_pay_1"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());
        // Reporting the same payment twice (browser + webhook) is harmless
        mvc.perform(pay(buyer, orderId, providerOrderId, "mock_pay_1")).andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(get("/api/artworks/" + id)).andExpect(jsonPath("$.status").value("SOLD"));
        mvc.perform(buy(otherBuyer, id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Sorry, this artwork has already been sold"));

        mvc.perform(authed(get("/api/me/sales"), artist)).andExpect(jsonPath("$[0].id").value(orderId));
        mvc.perform(authed(post("/api/orders/" + orderId + "/cancel"), buyer)).andExpect(status().isConflict());
        mvc.perform(payOrder(buyer, orderId)).andExpect(status().isConflict());
        mvc.perform(authed(post("/api/orders/" + orderId + "/complete"), buyer)).andExpect(status().isForbidden());
        mvc.perform(authed(post("/api/orders/" + orderId + "/complete"), artist))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void paymentIssuesInvoiceAndEmailsIt() throws Exception {
        String artist = register("ARTIST");
        String buyer = register("COLLECTOR");
        String stranger = register("COLLECTOR");
        long id = createArtwork(artist, "Invoice <Test> Piece", "FIXED", null);

        // The billing email is required and validated
        mvc.perform(authed(postJson("/api/artworks/" + id + "/purchase", Map.of("email", "not-an-email")), buyer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email must be a valid email address"));

        JsonNode checkout = body(mvc.perform(buy(buyer, id))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("buyer@example.com"))
                .andExpect(jsonPath("$.phone").doesNotExist()));
        long orderId = checkout.get("orderId").asLong();
        mvc.perform(authed(get("/api/orders/" + orderId + "/invoice"), buyer)).andExpect(status().isConflict());

        mvc.perform(pay(buyer, orderId, checkout.get("providerOrderId").asText(), "mock_pay_invoice"))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paymentMethod").value("UPI"))
                .andExpect(jsonPath("$.invoiceNumber").value(org.hamcrest.Matchers.matchesPattern("INV-\\d{4}-\\d{6}")));

        JsonNode invoice = awaitInvoiceNotifications(buyer, orderId, 1);
        String invoiceNumber = invoice.get("invoiceNumber").asText();
        assertThat(invoice.get("order").get("billingEmail").asText()).isEqualTo("buyer@example.com");
        assertThat(invoice.get("order").has("billingPhone")).isFalse();
        assertThat(invoice.get("notifications")).hasSize(1);
        assertThat(invoice.get("notifications").get(0).get("channel").asText()).isEqualTo("EMAIL");
        assertThat(invoice.get("notifications").get(0).get("status").asText()).isEqualTo("SIMULATED");
        assertThat(invoice.get("notifications").get(0).get("recipient").asText()).isEqualTo("buyer@example.com");

        // The demo outbox received the HTML invoice email (with escaped content)
        java.nio.file.Path outbox = java.nio.file.Path.of("target/test-outbox");
        try (var files = java.nio.file.Files.list(outbox)) {
            String email = files.filter(f -> f.getFileName().toString().contains("buyer@example.com"))
                    .map(f -> { try { return java.nio.file.Files.readString(f); } catch (java.io.IOException e) { throw new RuntimeException(e); } })
                    .filter(html -> html.contains(invoiceNumber))
                    .findFirst().orElseThrow();
            assertThat(email).contains("Invoice &lt;Test&gt; Piece").doesNotContain("Invoice <Test> Piece").contains("UPI");
        }
        assertThat(outbox.resolve("sms.log")).doesNotExist();

        // Buyer and seller can see the invoice; others can't. Only the buyer can resend it.
        mvc.perform(authed(get("/api/orders/" + orderId + "/invoice"), artist)).andExpect(status().isOk());
        mvc.perform(authed(get("/api/orders/" + orderId + "/invoice"), stranger)).andExpect(status().isForbidden());
        mvc.perform(authed(post("/api/orders/" + orderId + "/invoice/send"), artist)).andExpect(status().isForbidden());
        mvc.perform(authed(post("/api/orders/" + orderId + "/invoice/send"), buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.length()").value(2));
    }

    @Test
    void unpaidOrdersCanBeCancelledOrExpire() throws Exception {
        String artist = register("ARTIST");
        String buyer = register("COLLECTOR");
        String slowBuyer = register("COLLECTOR");
        String thirdBuyer = register("COLLECTOR");
        long id = createArtwork(artist, "Held piece", "FIXED", null);

        long cancelled = body(mvc.perform(buy(buyer, id))).get("orderId").asLong();
        mvc.perform(authed(post("/api/orders/" + cancelled + "/cancel"), slowBuyer)).andExpect(status().isForbidden());
        mvc.perform(authed(post("/api/orders/" + cancelled + "/cancel"), buyer))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/artworks/" + id)).andExpect(jsonPath("$.status").value("AVAILABLE"));

        // The slow buyer's reservation lapses and the artwork goes back on sale
        JsonNode slow = body(mvc.perform(buy(slowBuyer, id)));
        long slowOrder = slow.get("orderId").asLong();
        expireHold(slowOrder);
        paymentService.releaseExpiredHolds();
        mvc.perform(get("/api/artworks/" + id)).andExpect(jsonPath("$.status").value("AVAILABLE"));
        mvc.perform(authed(get("/api/me/orders"), slowBuyer)).andExpect(jsonPath("$[0].status").value("CANCELLED"));

        // Someone else reserves it, so the slow buyer's late payment can't be honoured
        body(mvc.perform(buy(thirdBuyer, id)).andExpect(status().isCreated()));
        mvc.perform(pay(slowBuyer, slowOrder, slow.get("providerOrderId").asText(), "mock_pay_late"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/artworks/" + id)).andExpect(jsonPath("$.status").value("RESERVED"));
    }

    @Test
    void latePaymentIsHonouredWhenArtworkIsStillAvailable() throws Exception {
        String artist = register("ARTIST");
        String buyer = register("COLLECTOR");
        long id = createArtwork(artist, "Patient piece", "FIXED", null);
        JsonNode checkout = body(mvc.perform(buy(buyer, id)));
        long orderId = checkout.get("orderId").asLong();
        expireHold(orderId);
        paymentService.releaseExpiredHolds();

        mvc.perform(pay(buyer, orderId, checkout.get("providerOrderId").asText(), "mock_pay_late"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(get("/api/artworks/" + id)).andExpect(jsonPath("$.status").value("SOLD"));
    }

    @Test
    void webhookIsAcknowledgedInDemoMode() throws Exception {
        mvc.perform(post("/api/payments/razorpay/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    // ---------- Auctions ----------

    @Test
    void auctionBiddingRulesAndSettlement() throws Exception {
        String artist = register("ARTIST");
        String alice = register("COLLECTOR");
        String bob = register("COLLECTOR");
        long id = createArtwork(artist, "Auction piece", "AUCTION", 48);

        mvc.perform(get("/api/artworks/" + id))
                .andExpect(jsonPath("$.auctionLive").value(true))
                .andExpect(jsonPath("$.minimumBid").value(1500.0));
        mvc.perform(buy(alice, id)).andExpect(status().isBadRequest());
        mvc.perform(bid(artist, id, 2000)).andExpect(status().isBadRequest());
        mvc.perform(bid(alice, id, 1499)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Your bid must be at least 1500.00"));

        mvc.perform(bid(alice, id, 1500)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentBid").value(1500.0))
                .andExpect(jsonPath("$.minimumBid").value(1575.0)) // +5%
                .andExpect(jsonPath("$.bidCount").value(1));
        mvc.perform(bid(alice, id, 1600)).andExpect(status().isConflict()); // already leading
        mvc.perform(bid(bob, id, 1574)).andExpect(status().isBadRequest());
        mvc.perform(bid(bob, id, 1575)).andExpect(status().isCreated());
        mvc.perform(bid(alice, id, 2000)).andExpect(status().isCreated());

        mvc.perform(get("/api/artworks/" + id + "/bids"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].amount").value(2000.0));
        mvc.perform(get("/api/artworks").param("liveAuctions", "true").param("q", "Auction piece"))
                .andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(authed(delete("/api/artworks/" + id), artist)).andExpect(status().isConflict());

        endAuctionNow(id);
        mvc.perform(bid(bob, id, 5000)).andExpect(status().isConflict());
        auctionService.closeEndedAuctions();

        // The winner gets an unpaid order and the artwork is held for them (no expiry for auction wins)
        mvc.perform(get("/api/artworks/" + id)).andExpect(jsonPath("$.status").value("RESERVED"));
        JsonNode order = body(mvc.perform(authed(get("/api/me/orders"), alice))
                .andExpect(jsonPath("$[0].source").value("AUCTION"))
                .andExpect(jsonPath("$[0].status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$[0].price").value(2000.0))).get(0);
        assertThat(order.get("holdExpiresAt").isNull()).isTrue();
        mvc.perform(authed(get("/api/me/bids"), alice)).andExpect(jsonPath("$[0].won").value(true));
        mvc.perform(authed(get("/api/me/bids"), bob))
                .andExpect(jsonPath("$[0].leading").value(false))
                .andExpect(jsonPath("$[0].won").value(false))
                .andExpect(jsonPath("$[0].myHighestBid").value(1575.0));

        long orderId = order.get("id").asLong();
        mvc.perform(payOrder(bob, orderId)).andExpect(status().isForbidden());
        JsonNode checkout = body(mvc.perform(payOrder(alice, orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(200000)));
        mvc.perform(pay(alice, orderId, checkout.get("providerOrderId").asText(), "mock_pay_auction"))
                .andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(get("/api/artworks/" + id)).andExpect(jsonPath("$.status").value("SOLD"));
    }

    @Test
    void auctionWithoutBidsBecomesFixedPriceListing() throws Exception {
        String artist = register("ARTIST");
        long id = createArtwork(artist, "Unbid auction", "AUCTION", 1);
        endAuctionNow(id);
        auctionService.closeEndedAuctions();
        mvc.perform(get("/api/artworks/" + id))
                .andExpect(jsonPath("$.saleType").value("FIXED"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        mvc.perform(buy(register("COLLECTOR"), id))
                .andExpect(status().isCreated());
    }

    // ---------- Reviews, artist profiles, exhibitions ----------

    @Test
    void reviewsUpdateRatingsAndArtistProfile() throws Exception {
        String artist = register("ARTIST");
        long artistId = me(artist).get("id").asLong();
        String fan = register("COLLECTOR");
        String critic = register("COLLECTOR");
        long id = createArtwork(artist, "Reviewed work", "FIXED", null);

        mvc.perform(review(artist, id, 5)).andExpect(status().isBadRequest());
        mvc.perform(review(fan, id, 6)).andExpect(status().isBadRequest());
        mvc.perform(review(fan, id, 5)).andExpect(status().isCreated());
        mvc.perform(review(fan, id, 4)).andExpect(status().isConflict());
        long criticReview = body(mvc.perform(review(critic, id, 2)).andExpect(status().isCreated())).get("id").asLong();

        mvc.perform(get("/api/artworks/" + id))
                .andExpect(jsonPath("$.averageRating").value(3.5))
                .andExpect(jsonPath("$.reviewCount").value(2));
        mvc.perform(get("/api/artists/" + artistId))
                .andExpect(jsonPath("$.averageRating").value(3.5))
                .andExpect(jsonPath("$.artworks.length()").value(1));

        mvc.perform(authed(delete("/api/reviews/" + criticReview), fan)).andExpect(status().isForbidden());
        mvc.perform(authed(delete("/api/reviews/" + criticReview), critic)).andExpect(status().isNoContent());
        mvc.perform(get("/api/artworks/" + id + "/reviews")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/artworks/" + id)).andExpect(jsonPath("$.averageRating").value(5.0));

        mvc.perform(get("/api/artists")).andExpect(status().isOk());
        long collectorId = me(fan).get("id").asLong();
        mvc.perform(get("/api/artists/" + collectorId)).andExpect(status().isNotFound());
    }

    @Test
    void exhibitionsShowcaseTheCuratorsOwnWork() throws Exception {
        String artist = register("ARTIST");
        String otherArtist = register("ARTIST");
        long first = createArtwork(artist, "Exhibit A", "FIXED", null);
        long second = createArtwork(artist, "Exhibit B", "FIXED", null);
        long foreign = createArtwork(otherArtist, "Not mine", "FIXED", null);
        LocalDate today = LocalDate.now();

        mvc.perform(exhibition(register("COLLECTOR"), post("/api/exhibitions"), "Nope", today, today, first))
                .andExpect(status().isForbidden());
        mvc.perform(exhibition(artist, post("/api/exhibitions"), "Mixed", today, today, first, foreign))
                .andExpect(status().isForbidden());
        mvc.perform(exhibition(artist, post("/api/exhibitions"), "Backwards", today, today.minusDays(1), first))
                .andExpect(status().isBadRequest());

        long exhibitionId = body(mvc.perform(exhibition(artist, post("/api/exhibitions"), "Blue Period",
                        today.minusDays(1), today.plusDays(7), second, first))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exhibition.status").value("ONGOING"))
                .andExpect(jsonPath("$.artworks[0].title").value("Exhibit B"))).get("exhibition").get("id").asLong();

        mvc.perform(get("/api/exhibitions").param("status", "ONGOING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + exhibitionId + ")].artworkCount").value(2));
        mvc.perform(exhibition(otherArtist, put("/api/exhibitions/" + exhibitionId), "Hijack", today, today, foreign))
                .andExpect(status().isForbidden());

        // Deleting an artwork removes it from the exhibition without leaving a gap
        mvc.perform(authed(delete("/api/artworks/" + second), artist)).andExpect(status().isNoContent());
        mvc.perform(get("/api/exhibitions/" + exhibitionId))
                .andExpect(jsonPath("$.artworks.length()").value(1))
                .andExpect(jsonPath("$.artworks[0].title").value("Exhibit A"));

        mvc.perform(authed(delete("/api/exhibitions/" + exhibitionId), artist)).andExpect(status().isNoContent());
        mvc.perform(get("/api/exhibitions/" + exhibitionId)).andExpect(status().isNotFound());
    }

    // ---------- Helpers ----------

    private String register(String role) throws Exception {
        String username = role.toLowerCase() + USER_COUNTER.incrementAndGet();
        return body(mvc.perform(postJson("/api/auth/register",
                        Map.of("username", username, "password", "secret1", "role", role)))
                .andExpect(status().isCreated())).get("token").asText();
    }

    private JsonNode me(String token) throws Exception {
        return body(mvc.perform(authed(get("/api/me"), token)).andExpect(status().isOk()));
    }

    private long createArtwork(String token, String title, String saleType, Integer hours) throws Exception {
        return body(mvc.perform(uploadArtwork(token, title, saleType, hours)).andExpect(status().isCreated()))
                .get("id").asLong();
    }

    private MockMultipartHttpServletRequestBuilder uploadArtwork(String token, String title, String saleType,
                                                                 Integer hours) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/artworks").file(image());
        request.param("title", title).param("description", "A test artwork").param("price", "1500")
                .param("category", "Painting").param("tags", "Sea, blue, sea").param("saleType", saleType);
        if (hours != null) request.param("auctionDurationHours", hours.toString());
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("image", "art.png", "image/png", new byte[] { 1, 2, 3 });
    }

    private MockHttpServletRequestBuilder bid(String token, long artworkId, double amount) throws Exception {
        return authed(postJson("/api/artworks/" + artworkId + "/bids", Map.of("amount", amount)), token);
    }

    private MockHttpServletRequestBuilder review(String token, long artworkId, int rating) throws Exception {
        return authed(postJson("/api/artworks/" + artworkId + "/reviews",
                Map.of("rating", rating, "comment", "Lovely brushwork")), token);
    }

    private MockHttpServletRequestBuilder exhibition(String token, MockHttpServletRequestBuilder request, String title,
                                                     LocalDate start, LocalDate end, Long... artworkIds) throws Exception {
        return authed(request, token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "title", title, "description", "An exhibition", "startDate", start.toString(),
                "endDate", end.toString(), "artworkIds", artworkIds)));
    }

    private static final Map<String, String> CONTACT = Map.of("email", "Buyer@Example.com");

    /** Starts buying a fixed-price artwork with a valid billing email. */
    private MockHttpServletRequestBuilder buy(String token, long artworkId) throws Exception {
        return authed(postJson("/api/artworks/" + artworkId + "/purchase", CONTACT), token);
    }

    /** Opens payment for an existing unpaid order (e.g. a won auction). */
    private MockHttpServletRequestBuilder payOrder(String token, long orderId) throws Exception {
        return authed(postJson("/api/orders/" + orderId + "/checkout", CONTACT), token);
    }

    private MockHttpServletRequestBuilder pay(String token, long orderId, String providerOrderId, String paymentId)
            throws Exception {
        return authed(postJson("/api/orders/" + orderId + "/payment", Map.of(
                "providerOrderId", providerOrderId, "paymentId", paymentId, "signature", "demo", "method", "UPI")), token);
    }

    /** Invoices are emailed in the background; wait until the expected deliveries have been recorded. */
    private JsonNode awaitInvoiceNotifications(String token, long orderId, int expected) throws Exception {
        JsonNode invoice = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            invoice = body(mvc.perform(authed(get("/api/orders/" + orderId + "/invoice"), token)).andExpect(status().isOk()));
            if (invoice.get("notifications").size() >= expected) return invoice;
            Thread.sleep(100);
        }
        return invoice;
    }

    private void expireHold(long orderId) {
        transactionTemplate.executeWithoutResult(status -> entityManager
                .createQuery("update Order o set o.holdExpiresAt = :past where o.id = :id")
                .setParameter("past", Instant.now().minusSeconds(1))
                .setParameter("id", orderId)
                .executeUpdate());
    }

    private void endAuctionNow(long artworkId) {
        transactionTemplate.executeWithoutResult(status -> artworkRepository.findById(artworkId).orElseThrow()
                .setAuctionEndsAt(Instant.now().minusSeconds(1)));
    }

    private MockHttpServletRequestBuilder postJson(String url, Object payload) throws Exception {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        String content = result.andReturn().getResponse().getContentAsString();
        assertThat(content).isNotBlank();
        return json.readTree(content);
    }
}
