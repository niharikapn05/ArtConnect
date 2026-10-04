package com.example.ArtConnect.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.example.ArtConnect.dto.ArtworkDtos.ArtworkResponse;
import com.example.ArtConnect.model.Bid;
import com.example.ArtConnect.model.Notification;
import com.example.ArtConnect.model.Order;
import com.example.ArtConnect.model.OrderSource;
import com.example.ArtConnect.model.OrderStatus;
import com.example.ArtConnect.model.Review;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Orders, bids and reviews. */
public final class MarketDtos {
    private MarketDtos() {}

    public record OrderResponse(Long id, Long artworkId, String artworkTitle, String imageUrl, BigDecimal price,
                                OrderSource source, OrderStatus status, Long buyerId, String buyerName,
                                Long artistId, String artistName, Instant createdAt, Instant updatedAt,
                                String paymentId, Instant paidAt, Instant holdExpiresAt,
                                String paymentMethod, String invoiceNumber, String billingEmail, String billingPhone) {
        public static OrderResponse from(Order o) {
            return new OrderResponse(o.getId(), o.getArtwork() == null ? null : o.getArtwork().getId(),
                    o.getArtworkTitle(), o.getImageUrl(), o.getPrice(), o.getSource(), o.getStatus(),
                    o.getBuyer().getId(), o.getBuyer().getDisplayName(),
                    o.getArtist().getId(), o.getArtist().getDisplayName(), o.getCreatedAt(), o.getUpdatedAt(),
                    o.getPaymentId(), o.getPaidAt(), o.getHoldExpiresAt(),
                    o.getPaymentMethod(), o.getInvoiceNumber(), o.getBillingEmail(), o.getBillingPhone());
        }
    }

    /** Billing contact entered at checkout; the invoice is emailed and texted here. */
    public record CheckoutRequest(
            @NotBlank @Email(message = "must be a valid email address") @Size(max = 254) String email,
            // Loose shape check; PaymentService.normalizeIndianMobile enforces a real 10-digit Indian number
            @NotBlank @Pattern(regexp = "^\\+?[0-9][0-9\\s-]{8,15}$",
                    message = "must be a valid 10-digit Indian mobile number") String phone) {}

    /**
     * Everything the browser needs to open the payment gateway's checkout.
     * amount is in the smallest currency unit (paise for INR), as gateways expect.
     */
    public record CheckoutResponse(Long orderId, String provider, String keyId, String providerOrderId,
                                   long amount, String currency, String artworkTitle, String buyerName,
                                   Instant holdExpiresAt, String email, String phone) {}

    /** What the gateway's checkout returns to the browser after a successful payment. */
    public record PaymentConfirmation(@NotBlank @Size(max = 64) String providerOrderId,
                                      @NotBlank @Size(max = 64) String paymentId,
                                      @NotBlank @Size(max = 256) String signature,
                                      @Pattern(regexp = "UPI|CARD|NETBANKING|WALLET") String method) {}

    public record NotificationResponse(String channel, String recipient, String status, String provider,
                                       String error, Instant createdAt) {
        public static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getChannel().name(), n.getRecipient(), n.getStatus().name(),
                    n.getProvider(), n.getError(), n.getCreatedAt());
        }
    }

    /** Everything shown on an invoice, plus where it was sent. */
    public record InvoiceResponse(String invoiceNumber, Instant issuedAt, OrderResponse order,
                                  String sellerName, String sellerLocation, String buyerName,
                                  String currency, List<NotificationResponse> notifications) {}

    public record BidRequest(@NotNull @DecimalMin("1") @Digits(integer = 10, fraction = 2) BigDecimal amount) {}

    public record BidResponse(Long id, BigDecimal amount, Long bidderId, String bidderName, Instant createdAt) {
        public static BidResponse from(Bid b) {
            return new BidResponse(b.getId(), b.getAmount(), b.getBidder().getId(),
                    b.getBidder().getDisplayName(), b.getCreatedAt());
        }
    }

    /** An auction the current user has bid on, with how they stand. */
    public record MyBidResponse(ArtworkResponse artwork, BigDecimal myHighestBid, boolean leading, boolean won) {}

    public record ReviewRequest(@Min(1) @Max(5) int rating, @NotBlank @Size(max = 1000) String comment) {}

    public record ReviewResponse(Long id, Long artworkId, int rating, String comment, Long authorId,
                                 String authorName, String authorAvatarUrl, Instant createdAt) {
        public static ReviewResponse from(Review r) {
            return new ReviewResponse(r.getId(), r.getArtwork().getId(), r.getRating(), r.getComment(),
                    r.getAuthor().getId(), r.getAuthor().getDisplayName(), r.getAuthor().getAvatarUrl(),
                    r.getCreatedAt());
        }
    }
}
