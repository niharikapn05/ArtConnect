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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    void artworkCanOnlyBeBoughtOnceAndCancellationRelistsIt() throws Exception {
        String artist = register("ARTIST");
        String buyer = register("COLLECTOR");
        String lateBuyer = register("COLLECTOR");
        long id = createArtwork(artist, "One of a kind", "FIXED", null);

        mvc.perform(authed(post("/api/artworks/" + id + "/purchase"), artist)).andExpect(status().isBadRequest());
        long orderId = body(mvc.perform(authed(post("/api/artworks/" + id + "/purchase"), buyer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price").value(1500.0))
                .andExpect(jsonPath("$.status").value("PENDING"))).get("id").asLong();
        mvc.perform(authed(post("/api/artworks/" + id + "/purchase"), lateBuyer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Sorry, this artwork has already been sold"));
        mvc.perform(get("/api/artworks/" + id)).andExpect(jsonPath("$.status").value("SOLD"));

        mvc.perform(authed(get("/api/me/orders"), buyer)).andExpect(jsonPath("$[0].artworkTitle").value("One of a kind"));
        mvc.perform(authed(get("/api/me/sales"), artist)).andExpect(jsonPath("$[0].id").value(orderId));

        mvc.perform(authed(post("/api/orders/" + orderId + "/cancel"), lateBuyer)).andExpect(status().isForbidden());
        mvc.perform(authed(post("/api/orders/" + orderId + "/cancel"), buyer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/api/artworks/" + id)).andExpect(jsonPath("$.status").value("AVAILABLE"));

        long secondOrder = body(mvc.perform(authed(post("/api/artworks/" + id + "/purchase"), lateBuyer))
                .andExpect(status().isCreated())).get("id").asLong();
        mvc.perform(authed(post("/api/orders/" + secondOrder + "/complete"), lateBuyer)).andExpect(status().isForbidden());
        mvc.perform(authed(post("/api/orders/" + secondOrder + "/complete"), artist))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(authed(post("/api/orders/" + secondOrder + "/cancel"), lateBuyer)).andExpect(status().isConflict());
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
        mvc.perform(authed(post("/api/artworks/" + id + "/purchase"), alice)).andExpect(status().isBadRequest());
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

        mvc.perform(get("/api/artworks/" + id)).andExpect(jsonPath("$.status").value("SOLD"));
        mvc.perform(authed(get("/api/me/orders"), alice))
                .andExpect(jsonPath("$[0].source").value("AUCTION"))
                .andExpect(jsonPath("$[0].price").value(2000.0));
        mvc.perform(authed(get("/api/me/bids"), alice)).andExpect(jsonPath("$[0].won").value(true));
        mvc.perform(authed(get("/api/me/bids"), bob))
                .andExpect(jsonPath("$[0].leading").value(false))
                .andExpect(jsonPath("$[0].myHighestBid").value(1575.0));
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
        mvc.perform(authed(post("/api/artworks/" + id + "/purchase"), register("COLLECTOR")))
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
