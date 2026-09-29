package com.example.ArtConnect.model;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null once the artwork has been deleted; the snapshot fields below keep the order readable. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "artwork_id")
    private Artwork artwork;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id")
    private User buyer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "artist_id")
    private User artist;

    @Column(name = "artwork_title", nullable = false, length = 120)
    private String artworkTitle;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Payment gateway details
    @Column(name = "payment_provider", length = 20)
    private String paymentProvider;

    /** The gateway's order id (e.g. Razorpay "order_..."), created at checkout. */
    @Column(name = "provider_order_id", length = 64, unique = true)
    private String providerOrderId;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** An unpaid order is cancelled and the artwork released after this time; null means no expiry. */
    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    protected Order() {}

    public Order(Artwork artwork, User buyer, OrderSource source, BigDecimal price, Instant holdExpiresAt) {
        this.artwork = artwork;
        this.buyer = buyer;
        this.artist = artwork.getArtist();
        this.artworkTitle = artwork.getTitle();
        this.imageUrl = artwork.getImageUrl();
        this.price = price;
        this.source = source;
        this.status = OrderStatus.AWAITING_PAYMENT;
        this.holdExpiresAt = holdExpiresAt;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void changeStatus(OrderStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void attachPaymentOrder(String provider, String providerOrderId) {
        this.paymentProvider = provider;
        this.providerOrderId = providerOrderId;
        this.updatedAt = Instant.now();
    }

    public void markPaid(String paymentId) {
        this.paymentId = paymentId;
        this.paidAt = Instant.now();
        this.holdExpiresAt = null;
        changeStatus(OrderStatus.PAID);
    }


    public Long getId() { return id; }
    public Artwork getArtwork() { return artwork; }
    public User getBuyer() { return buyer; }
    public User getArtist() { return artist; }
    public String getArtworkTitle() { return artworkTitle; }
    public String getImageUrl() { return imageUrl; }
    public BigDecimal getPrice() { return price; }
    public OrderSource getSource() { return source; }
    public OrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getPaymentProvider() { return paymentProvider; }
    public String getProviderOrderId() { return providerOrderId; }
    public String getPaymentId() { return paymentId; }
    public Instant getPaidAt() { return paidAt; }
    public Instant getHoldExpiresAt() { return holdExpiresAt; }
}
