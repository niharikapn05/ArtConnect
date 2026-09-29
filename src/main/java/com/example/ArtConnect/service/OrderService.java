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

@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final ArtworkRepository artworkRepository;

    public OrderService(OrderRepository orderRepository, ArtworkRepository artworkRepository) {
        this.orderRepository = orderRepository;
        this.artworkRepository = artworkRepository;
    }

    /** Buys a fixed-price artwork. The row lock guarantees only one buyer can get it. */
    @Transactional
    public OrderResponse purchase(User buyer, Long artworkId) {
        Artwork artwork = artworkRepository.findByIdForUpdate(artworkId)
                .orElseThrow(() -> ApiException.notFound("Artwork not found"));
        if (artwork.isAuction()) {
            throw ApiException.badRequest("This artwork is sold by auction. Place a bid instead.");
        }
        if (artwork.getStatus() != ArtworkStatus.AVAILABLE) {
            throw ApiException.conflict("Sorry, this artwork has already been sold");
        }
        if (artwork.isOwnedBy(buyer)) {
            throw ApiException.badRequest("You can't buy your own artwork");
        }
        artwork.setStatus(ArtworkStatus.SOLD);
        return OrderResponse.from(orderRepository.save(
                new Order(artwork, buyer, OrderSource.PURCHASE, artwork.getPrice())));
    }

    /** Called when an auction closes with a winner; the caller holds the artwork lock. */
    Order createAuctionOrder(Artwork artwork) {
        artwork.setStatus(ArtworkStatus.SOLD);
        return orderRepository.save(
                new Order(artwork, artwork.getHighestBidder(), OrderSource.AUCTION, artwork.getCurrentBid()));
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

    /** The buyer cancels a pending order; the artwork goes back on sale at its listed price. */
    @Transactional
    public OrderResponse cancel(User user, Long orderId) {
        Order order = findPending(orderId);
        if (!order.getBuyer().sameAs(user) && !user.isAdmin()) {
            throw ApiException.forbidden("You can only cancel your own orders");
        }
        order.changeStatus(OrderStatus.CANCELLED);
        if (order.getArtwork() != null) {
            artworkRepository.findByIdForUpdate(order.getArtwork().getId()).ifPresent(artwork -> {
                artwork.setStatus(ArtworkStatus.AVAILABLE);
                artwork.setSaleType(SaleType.FIXED);
            });
        }
        return OrderResponse.from(order);
    }

    /** The artist confirms payment and delivery. */
    @Transactional
    public OrderResponse complete(User user, Long orderId) {
        Order order = findPending(orderId);
        if (!order.getArtist().sameAs(user) && !user.isAdmin()) {
            throw ApiException.forbidden("Only the artist can complete this order");
        }
        order.changeStatus(OrderStatus.COMPLETED);
        return OrderResponse.from(order);
    }

    private Order findPending(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw ApiException.conflict("This order is already " + order.getStatus().name().toLowerCase());
        }
        return order;
    }
}
