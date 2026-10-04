package com.example.ArtConnect.notification;

/** Sends invoice text messages. Chosen with app.notifications.sms.provider: "outbox" (default demo) or "twilio". */
public interface SmsSender {
    String provider();

    boolean simulated();

    void send(String to, String text) throws Exception;
}
