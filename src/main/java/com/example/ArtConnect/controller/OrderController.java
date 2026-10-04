package com.example.ArtConnect.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ArtConnect.dto.MarketDtos.CheckoutRequest;
import com.example.ArtConnect.dto.MarketDtos.CheckoutResponse;
import com.example.ArtConnect.dto.MarketDtos.InvoiceResponse;
import com.example.ArtConnect.dto.MarketDtos.OrderResponse;
import com.example.ArtConnect.dto.MarketDtos.PaymentConfirmation;
import com.example.ArtConnect.exception.ApiException;
import com.example.ArtConnect.model.User;
import com.example.ArtConnect.notification.InvoiceNotifier;
import com.example.ArtConnect.notification.InvoiceService;
import com.example.ArtConnect.security.CurrentUser;
import com.example.ArtConnect.service.OrderService;
import com.example.ArtConnect.service.PaymentService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final PaymentService paymentService;
    private final InvoiceService invoiceService;
    private final InvoiceNotifier invoiceNotifier;

    public OrderController(OrderService orderService, PaymentService paymentService,
                           InvoiceService invoiceService, InvoiceNotifier invoiceNotifier) {
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.invoiceService = invoiceService;
        this.invoiceNotifier = invoiceNotifier;
    }

    /** Opens payment for an unpaid order (e.g. a won auction, or resuming an interrupted checkout). */
    @PostMapping("/{orderId}/checkout")
    public CheckoutResponse checkout(@CurrentUser User user, @PathVariable Long orderId,
                                     @Valid @RequestBody CheckoutRequest contact) {
        return paymentService.checkout(user, orderId, contact);
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

    /** The invoice of a paid order, with where it was emailed and texted. Buyer, seller or admin only. */
    @GetMapping("/{orderId}/invoice")
    public InvoiceResponse invoice(@CurrentUser User user, @PathVariable Long orderId) {
        return invoiceService.invoiceFor(user, orderId);
    }

    /** Sends the invoice to the billing email and phone again. Buyer or admin only. */
    @PostMapping("/{orderId}/invoice/send")
    public InvoiceResponse resendInvoice(@CurrentUser User user, @PathVariable Long orderId) {
        InvoiceResponse invoice = invoiceService.invoiceFor(user, orderId);
        if (!user.getId().equals(invoice.order().buyerId()) && !user.isAdmin()) {
            throw ApiException.forbidden("Only the buyer can resend this invoice");
        }
        invoiceNotifier.sendInvoice(orderId);
        return invoiceService.invoiceFor(user, orderId);
    }
}
