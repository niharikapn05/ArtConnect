package com.example.ArtConnect.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ArtConnect.dto.MarketDtos.CheckoutResponse;
import com.example.ArtConnect.dto.MarketDtos.OrderResponse;
import com.example.ArtConnect.dto.MarketDtos.PaymentConfirmation;
import com.example.ArtConnect.exception.ApiException;
import com.example.ArtConnect.model.Artwork;
import com.example.ArtConnect.model.ArtworkStatus;
import com.example.ArtConnect.model.Order;
import com.example.ArtConnect.model.OrderSource;
import com.example.ArtConnect.model.OrderStatus;
import com.example.ArtConnect.model.User;
import com.example.ArtConnect.payment.PaymentGateway;
import com.example.ArtConnect.repository.ArtworkRepository;
import com.example.ArtConnect.repository.OrderRepository;

/**
 * Checkout flow:
 * <ol>
 *   <li>{@link #purchase} reserves the artwork, creates an AWAITING_PAYMENT order and a gateway order.</li>
 *   <li>The browser opens the gateway checkout and pays.</li>
 *   <li>{@link #confirm} verifies the gateway's signature and marks the order PAID (the webhook does the same
 *       if the browser never reports back).</li>
 * </ol>
 * Unpaid purchase reservations are released after app.payments.hold-minutes.
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final OrderRepository orderRepository;
    private final ArtworkRepository artworkRepository;
    private final OrderService orderService;
    private final PaymentGateway gateway;
    private final TransactionTemplate transactionTemplate;
    private final String currency;
    private final Duration holdDuration;

    public PaymentService(OrderRepository orderRepository, ArtworkRepository artworkRepository,
                          OrderService orderService, PaymentGateway gateway, TransactionTemplate transactionTemplate,
                          @Value("${app.payments.currency:INR}") String currency,
                          @Value("${app.payments.hold-minutes:30}") long holdMinutes) {
        this.orderRepository = orderRepository;
        this.artworkRepository = artworkRepository;
        this.orderService = orderService;
        this.gateway = gateway;
        this.transactionTemplate = transactionTemplate;
        this.currency = currency;
        this.holdDuration = Duration.ofMinutes(holdMinutes);
    }

    /** Starts buying a fixed-price artwork. Calling it again for the same buyer resumes their checkout. */
    @Transactional
    public CheckoutResponse purchase(User buyer, Long artworkId) {
        Artwork artwork = artworkRepository.findByIdForUpdate(artworkId)
                .orElseThrow(() -> ApiException.notFound("Artwork not found"));
        if (artwork.isAuction()) {
            throw ApiException.badRequest("This artwork is sold by auction. Place a bid instead.");
        }
        if (artwork.isOwnedBy(buyer)) {
            throw ApiException.badRequest("You can't buy your own artwork");
        }
        if (artwork.getStatus() == ArtworkStatus.RESERVED) {
            Order mine = orderRepository
                    .findFirstByArtworkIdAndBuyerIdAndStatus(artworkId, buyer.getId(), OrderStatus.AWAITING_PAYMENT)
                    .orElseThrow(() -> ApiException.conflict(
                            "Another buyer is completing payment for this artwork. Please check back soon."));
            return checkoutFor(mine);
        }
        if (artwork.getStatus() != ArtworkStatus.AVAILABLE) {
            throw ApiException.conflict("Sorry, this artwork has already been sold");
        }
        artwork.setStatus(ArtworkStatus.RESERVED);
        Order order = orderRepository.save(new Order(artwork, buyer, OrderSource.PURCHASE, artwork.getPrice(),
                Instant.now().plus(holdDuration)));
        return checkoutFor(order);
    }

    /** Opens (or re-opens) payment for an unpaid order, e.g. a won auction. */
    @Transactional
    public CheckoutResponse checkout(User buyer, Long orderId) {
        Order order = lockOrder(orderId);
        requireBuyer(order, buyer);
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw ApiException.conflict(order.getStatus() == OrderStatus.CANCELLED
                    ? "This order has been cancelled" : "This order has already been paid");
        }
        return checkoutFor(order);
    }

    /** Verifies the payment the browser reports and marks the order paid. */
    @Transactional
    public OrderResponse confirm(User buyer, Long orderId, PaymentConfirmation confirmation) {
        Order order = lockOrder(orderId);
        requireBuyer(order, buyer);
        if (!confirmation.providerOrderId().equals(order.getProviderOrderId())) {
            throw ApiException.badRequest("This payment does not belong to this order");
        }
        if (!gateway.verifyPayment(confirmation.providerOrderId(), confirmation.paymentId(), confirmation.signature())) {
            log.warn("Rejected payment {} for order {}: signature mismatch", confirmation.paymentId(), orderId);
            throw ApiException.badRequest("The payment could not be verified");
        }
        return OrderResponse.from(recordPayment(order, confirmation.paymentId()));
    }

    /** Handles the gateway's server-to-server notification; safe to receive more than once. */
    public void handleWebhook(String payload, String signature) {
        gateway.parsePaymentWebhook(payload, signature).ifPresent(paid -> {
            Optional<Long> orderId = orderRepository.findByProviderOrderId(paid.providerOrderId()).map(Order::getId);
            if (orderId.isEmpty()) {
                log.warn("Webhook for unknown payment order {}", paid.providerOrderId());
                return;
            }
            try {
                transactionTemplate.executeWithoutResult(status ->
                        recordPayment(lockOrder(orderId.get()), paid.paymentId()));
            } catch (ApiException e) {
                // Acknowledge anyway so the gateway stops retrying; the log explains what needs attention
                log.error("Webhook payment {} for order {} not applied: {}", paid.paymentId(), orderId.get(), e.getMessage());
            }
        });
    }

    /** Releases artworks whose buyers didn't pay in time. Each order is handled in its own transaction. */
    @Scheduled(fixedDelayString = "${app.payments.expiry-check-ms:60000}")
    public void releaseExpiredHolds() {
        for (Long id : orderRepository.findExpiredHoldIds(Instant.now())) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Order order = lockOrder(id);
                    // Re-check under the lock: the payment may have just been confirmed
                    if (order.getStatus() == OrderStatus.AWAITING_PAYMENT && order.getHoldExpiresAt() != null
                            && !order.getHoldExpiresAt().isAfter(Instant.now())) {
                        orderService.cancelAndRelease(order);
                        log.info("Released artwork for unpaid order {}", id);
                    }
                });
            } catch (RuntimeException e) {
                log.error("Failed to release unpaid order {}", id, e);
            }
        }
    }

    private CheckoutResponse checkoutFor(Order order) {
        if (order.getProviderOrderId() == null || !gateway.name().equals(order.getPaymentProvider())) {
            String providerOrderId = gateway.createOrder(toSmallestUnit(order.getPrice()), currency,
                    "artconnect_order_" + order.getId());
            order.attachPaymentOrder(gateway.name(), providerOrderId);
        }
        return new CheckoutResponse(order.getId(), gateway.name(), gateway.publicKey(), order.getProviderOrderId(),
                toSmallestUnit(order.getPrice()), currency, order.getArtworkTitle(),
                order.getBuyer().getDisplayName(), order.getHoldExpiresAt());
    }

    /** Marks the order paid and the artwork sold. The caller holds the order lock. */
    private Order recordPayment(Order order, String paymentId) {
        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.COMPLETED) {
            return order; // already recorded (browser and webhook both report the same payment)
        }
        Artwork artwork = order.getArtwork() == null ? null
                : artworkRepository.findByIdForUpdate(order.getArtwork().getId()).orElse(null);
        if (order.getStatus() == OrderStatus.CANCELLED
                && (artwork == null || artwork.getStatus() != ArtworkStatus.AVAILABLE)) {
            // Paid after the reservation lapsed and someone else got the artwork
            log.error("REFUND NEEDED: payment {} arrived for cancelled order {} whose artwork is no longer available",
                    paymentId, order.getId());
            throw ApiException.conflict("Your payment arrived after the reservation expired and the artwork is no "
                    + "longer available. You will be refunded. Payment reference: " + paymentId);
        }
        order.markPaid(paymentId);
        if (artwork != null) artwork.setStatus(ArtworkStatus.SOLD);
        return order;
    }

    private Order lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
    }

    private static void requireBuyer(Order order, User user) {
        if (!order.getBuyer().sameAs(user)) {
            throw ApiException.forbidden("This isn't your order");
        }
    }

    /** Rupees to paise (gateways take integer amounts in the smallest unit). */
    static long toSmallestUnit(BigDecimal amount) {
        return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
