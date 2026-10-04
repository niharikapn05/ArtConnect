package com.example.ArtConnect.notification;

/** Published when an order's payment has been verified; the invoice is sent once the transaction commits. */
public record OrderPaidEvent(Long orderId) {}
