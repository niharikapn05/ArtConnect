package com.example.ArtConnect.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ArtConnect.dto.MarketDtos.OrderResponse;
import com.example.ArtConnect.exception.ApiException;
import com.example.ArtConnect.model.Artwork;
import com.example.ArtConnect.model.ArtworkStatus;
import com.example.ArtConnect.model.Order;
import com.example.ArtConnect.model.OrderSource;
import com.example.ArtConnect.model.OrderStatus;
import com.example.ArtConnect.model.SaleType;
import com.example.ArtConnect.model.User;
import com.example.ArtConnect.repository.ArtworkRepository;
import com.example.ArtConnect.repository.OrderRepository;

/** Order history and lifecycle. Checkout and payment live in {@link PaymentService}. */
@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final ArtworkRepository artworkRepository;

    public OrderService(OrderRepository orderRepository, ArtworkRepository artworkRepository) {
        this.orderRepository = orderRepository;
        this.artworkRepository = artworkRepository;
    }

    /**
     * Called when an auction closes with a winner; the caller holds the artwork lock.
     * The artwork stays reserved for the winner until they pay.
     */
    Order createAuctionOrder(Artwork artwork) {
        artwork.setStatus(ArtworkStatus.RESERVED);
        return orderRepository.save(new Order(artwork, artwork.getHighestBidder(), OrderSource.AUCTION,
                artwork.getCurrentBid(), null));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> purchasesOf(User buyer) {
        return orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyer.getId()).stream()
                .map(OrderResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> salesOf(User artist) {
        return orderRepository.findByArtistIdOrderByCreatedAtDesc(artist.getId()).stream()
                .map(OrderResponse::from).toList();
    }

    /** The buyer cancels before paying; the artwork goes back on sale at its listed price. */
    @Transactional
    public OrderResponse cancel(User user, Long orderId) {
        Order order = findWithStatus(orderId, OrderStatus.AWAITING_PAYMENT,
                "Paid orders can't be cancelled here. Please contact the artist.");
        if (!order.getBuyer().sameAs(user) && !user.isAdmin()) {
            throw ApiException.forbidden("You can only cancel your own orders");
        }
        cancelAndRelease(order);
        return OrderResponse.from(order);
    }

    /** The artist confirms the paid artwork has been delivered. */
    @Transactional
    public OrderResponse complete(User user, Long orderId) {
        Order order = findWithStatus(orderId, OrderStatus.PAID, "Only paid orders can be marked completed");
        if (!order.getArtist().sameAs(user) && !user.isAdmin()) {
            throw ApiException.forbidden("Only the artist can complete this order");
        }
        order.changeStatus(OrderStatus.COMPLETED);
        return OrderResponse.from(order);
    }

    /** Cancels an unpaid order and puts a reserved artwork back on sale. The caller holds the order lock. */
    void cancelAndRelease(Order order) {
        order.changeStatus(OrderStatus.CANCELLED);
        if (order.getArtwork() != null) {
            artworkRepository.findByIdForUpdate(order.getArtwork().getId())
                    .filter(artwork -> artwork.getStatus() == ArtworkStatus.RESERVED)
                    .ifPresent(artwork -> {
                        artwork.setStatus(ArtworkStatus.AVAILABLE);
                        artwork.setSaleType(SaleType.FIXED);
                    });
        }
    }

    private Order findWithStatus(Long orderId, OrderStatus expected, String wrongStatusMessage) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        if (order.getStatus() != expected) {
            throw ApiException.conflict(order.getStatus() == OrderStatus.CANCELLED
                    ? "This order has been cancelled" : wrongStatusMessage);
        }
        return order;
    }
}
