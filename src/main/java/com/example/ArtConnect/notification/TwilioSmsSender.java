package com.example.ArtConnect.notification;

import java.time.Duration;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Sends order-confirmation texts with Twilio's Messages API (https://www.twilio.com/docs/messaging/api/message-resource).
 *
 * Trial accounts can't send custom text: they may only set To, and a Body naming one of Twilio's predefined
 * templates (https://www.twilio.com/docs/usage/trials/try-out-sms). Set TWILIO_TRIAL_TEMPLATE (e.g.
 * sms_order_confirmation) to send that template instead; leave it empty on an upgraded account to send our own text.
 */
@Component
@ConditionalOnProperty(name = "app.notifications.sms.provider", havingValue = "twilio")
public class TwilioSmsSender implements SmsSender {
    static final Set<String> TRIAL_TEMPLATES = Set.of("sms_2fa", "sms_appointment_reminders", "sms_order_confirmation",
            "sms_delivery_updates", "sms_customer_support", "sms_marketing_promotions", "sms_event_notifications",
            "sms_account_alerts", "sms_feedback_surveys", "sms_internal_alerts");

    private final RestClient restClient;
    private final String accountSid;
    private final String fromNumber;
    private final String trialTemplate;

    @Autowired
    public TwilioSmsSender(RestClient.Builder builder,
                           @Value("${twilio.account-sid:}") String accountSid,
                           @Value("${twilio.auth-token:}") String authToken,
                           @Value("${twilio.from-number:}") String fromNumber,
                           @Value("${twilio.trial-template:}") String trialTemplate) {
        this(builder, accountSid, authToken, fromNumber, trialTemplate, true);
    }

    /** applyTimeouts is false in tests, whose builder is bound to a mock server. */
    TwilioSmsSender(RestClient.Builder builder, String accountSid, String authToken, String fromNumber,
                    String trialTemplate, boolean applyTimeouts) {
        this.trialTemplate = trialTemplate.trim();
        if (accountSid.isBlank() || authToken.isBlank()) {
            throw new IllegalStateException("app.notifications.sms.provider=twilio needs TWILIO_ACCOUNT_SID and "
                    + "TWILIO_AUTH_TOKEN to be set");
        }
        if (this.trialTemplate.isEmpty() && fromNumber.isBlank()) {
            throw new IllegalStateException("Set TWILIO_FROM_NUMBER, or on a Twilio trial account set "
                    + "TWILIO_TRIAL_TEMPLATE=sms_order_confirmation");
        }
        if (!this.trialTemplate.isEmpty() && !TRIAL_TEMPLATES.contains(this.trialTemplate)) {
            throw new IllegalStateException("TWILIO_TRIAL_TEMPLATE must be one of Twilio's trial templates, e.g. "
                    + "sms_order_confirmation (was: " + this.trialTemplate + ")");
        }
        if (applyTimeouts) {
            SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
            timeouts.setConnectTimeout(Duration.ofSeconds(5));
            timeouts.setReadTimeout(Duration.ofSeconds(15));
            builder.requestFactory(timeouts);
        }
        this.restClient = builder
                .baseUrl("https://api.twilio.com")
                .defaultHeaders(headers -> headers.setBasicAuth(accountSid, authToken))
                .build();
        this.accountSid = accountSid;
        this.fromNumber = fromNumber;
    }

    @Override public String provider() { return trialTemplate.isEmpty() ? "twilio" : "twilio-trial"; }
    @Override public boolean simulated() { return false; }

    @Override
    public void send(String to, String text) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", to);
        // Trial accounts must send from the trial number Twilio assigned to the verified recipient (error 572003
        // otherwise), so From is sent whenever it's configured
        if (!fromNumber.isBlank()) form.add("From", fromNumber);
        // Trial accounts can only send one of Twilio's predefined templates, named in Body
        form.add("Body", trialTemplate.isEmpty() ? text : trialTemplate);
        restClient.post()
                .uri("/2010-04-01/Accounts/{sid}/Messages.json", accountSid)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
    }
}
