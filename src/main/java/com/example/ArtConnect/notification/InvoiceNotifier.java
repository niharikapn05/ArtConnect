package com.example.ArtConnect.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.ArtConnect.model.Notification;
import com.example.ArtConnect.model.Notification.Channel;
import com.example.ArtConnect.model.Notification.Status;
import com.example.ArtConnect.notification.InvoiceService.InvoiceMessage;
import com.example.ArtConnect.repository.NotificationRepository;
import com.example.ArtConnect.repository.OrderRepository;

/**
 * Emails and texts the invoice once a payment is committed. Runs in the background so a slow or failing
 * email/SMS provider never affects the payment itself; every attempt is recorded as a Notification.
 */
@Component
public class InvoiceNotifier {
    private static final Logger log = LoggerFactory.getLogger(InvoiceNotifier.class);

    private final InvoiceService invoiceService;
    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final NotificationRepository notificationRepository;
    private final OrderRepository orderRepository;
    private final TransactionTemplate transactionTemplate;

    public InvoiceNotifier(InvoiceService invoiceService, EmailSender emailSender, SmsSender smsSender,
                           NotificationRepository notificationRepository, OrderRepository orderRepository,
                           TransactionTemplate transactionTemplate) {
        this.invoiceService = invoiceService;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
        this.notificationRepository = notificationRepository;
        this.orderRepository = orderRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderPaid(OrderPaidEvent event) {
        sendInvoice(event.orderId());
    }

    /** Sends the invoice to the billing email and phone saved at checkout. */
    public void sendInvoice(Long orderId) {
        InvoiceMessage message;
        try {
            message = invoiceService.messageFor(orderId);
        } catch (RuntimeException e) {
            log.error("Could not prepare the invoice for order {}", orderId, e);
            return;
        }
        if (message.email() != null) {
            Outcome outcome = attempt(() -> emailSender.send(message.email(), message.subject(), message.html(), message.text()),
                    emailSender.simulated(), "email", orderId);
            record(orderId, Channel.EMAIL, message.email(), message.subject(), outcome, emailSender.provider());
        }
        if (message.phone() != null) {
            Outcome outcome = attempt(() -> smsSender.send(message.phone(), message.sms()),
                    smsSender.simulated(), "SMS", orderId);
            record(orderId, Channel.SMS, message.phone(), null, outcome, smsSender.provider());
        }
    }

    private static Outcome attempt(Delivery delivery, boolean simulated, String what, Long orderId) {
        try {
            delivery.run();
            return new Outcome(simulated ? Status.SIMULATED : Status.SENT, null);
        } catch (Exception e) {
            log.warn("Invoice {} for order {} failed: {}", what, orderId, e.getMessage());
            return new Outcome(Status.FAILED, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void record(Long orderId, Channel channel, String recipient, String subject, Outcome outcome, String provider) {
        String trimmedSubject = subject == null || subject.length() <= 200 ? subject : subject.substring(0, 200);
        transactionTemplate.executeWithoutResult(tx -> notificationRepository.save(new Notification(
                orderRepository.getReferenceById(orderId), channel, recipient, trimmedSubject,
                outcome.status(), provider, outcome.error())));
    }

    private record Outcome(Status status, String error) {}

    @FunctionalInterface
    private interface Delivery {
        void run() throws Exception;
    }
}
