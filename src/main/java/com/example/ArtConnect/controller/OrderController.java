package com.example.ArtConnect.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ArtConnect.dto.MarketDtos.OrderResponse;
import com.example.ArtConnect.model.User;
import com.example.ArtConnect.security.CurrentUser;
import com.example.ArtConnect.service.OrderService;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
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
