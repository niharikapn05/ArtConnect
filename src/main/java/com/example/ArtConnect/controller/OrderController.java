package com.example.ArtConnect.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ArtConnect.dto.MarketDtos.CheckoutResponse;
import com.example.ArtConnect.dto.MarketDtos.OrderResponse;
import com.example.ArtConnect.dto.MarketDtos.PaymentConfirmation;
import com.example.ArtConnect.model.User;
import com.example.ArtConnect.security.CurrentUser;
import com.example.ArtConnect.service.OrderService;
import com.example.ArtConnect.service.PaymentService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final PaymentService paymentService;

    public OrderController(OrderService orderService, PaymentService paymentService) {
        this.orderService = orderService;
        this.paymentService = paymentService;
    }

    /** Opens payment for an unpaid order (e.g. a won auction, or resuming an interrupted checkout). */
    @PostMapping("/{orderId}/checkout")
    public CheckoutResponse checkout(@CurrentUser User user, @PathVariable Long orderId) {
        return paymentService.checkout(user, orderId);
    }

    /** The browser reports a completed payment; the server verifies it with the gateway's signature. */
    @PostMapping("/{orderId}/payment")
    public OrderResponse confirmPayment(@CurrentUser User user, @PathVariable Long orderId,
                                        @Valid @RequestBody PaymentConfirmation confirmation) {
        return paymentService.confirm(user, orderId, confirmation);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@CurrentUser User user, @PathVariable Long orderId) {
        return orderService.cancel(user, orderId);
    }

    @PostMapping("/{orderId}/complete")
    public OrderResponse complete(@CurrentUser User user, @PathVariable Long orderId) {
        return orderService.complete(user, orderId);
    }
}
