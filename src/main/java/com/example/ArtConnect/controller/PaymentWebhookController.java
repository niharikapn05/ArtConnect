package com.example.ArtConnect.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.ArtConnect.service.PaymentService;

/**
 * Server-to-server payment notifications. Point the Razorpay dashboard webhook
 * (events: payment.captured, order.paid) at https://your-domain/api/payments/razorpay/webhook.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentWebhookController {

    private final PaymentService paymentService;

    public PaymentWebhookController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/razorpay/webhook")
    public ResponseEntity<Void> razorpayWebhook(@RequestBody String payload,
                                                @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        paymentService.handleWebhook(payload, signature);
        return ResponseEntity.ok().build();
    }
}
