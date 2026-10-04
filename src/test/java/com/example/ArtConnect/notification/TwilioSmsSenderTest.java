package com.example.ArtConnect.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

class TwilioSmsSenderTest {
    private static final String URL = "https://api.twilio.com/2010-04-01/Accounts/AC123/Messages.json";

    @Test
    void upgradedAccountSendsOurOwnTextFromOurNumber() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer twilio = MockRestServiceServer.bindTo(builder).build();
        TwilioSmsSender sender = new TwilioSmsSender(builder, "AC123", "token", "+15005550006", "", false);

        twilio.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(form("+919876543210", "+15005550006", "Order #41 confirmed")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        sender.send("+919876543210", "Order #41 confirmed");
        twilio.verify();
        assertThat(sender.provider()).isEqualTo("twilio");
    }

    @Test
    void trialAccountSendsTwiliosPredefinedTemplateFromTheAssignedTrialNumber() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer twilio = MockRestServiceServer.bindTo(builder).build();
        TwilioSmsSender sender = new TwilioSmsSender(builder, "AC123", "token", "+18885550123", "sms_order_confirmation", false);

        twilio.expect(requestTo(URL))
                .andExpect(content().formData(form("+919876543210", "+18885550123", "sms_order_confirmation")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        sender.send("+919876543210", "Order #41 confirmed");
        twilio.verify();
        assertThat(sender.provider()).isEqualTo("twilio-trial");
    }

    @Test
    void trialTemplateWithoutAFromNumberLetsTwilioChooseTheSender() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer twilio = MockRestServiceServer.bindTo(builder).build();
        TwilioSmsSender sender = new TwilioSmsSender(builder, "AC123", "token", "", "sms_order_confirmation", false);

        twilio.expect(requestTo(URL))
                .andExpect(content().formData(form("+919876543210", null, "sms_order_confirmation")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        sender.send("+919876543210", "Order #41 confirmed");
        twilio.verify();
    }

    @Test
    void explainsMissingOrInvalidSettings() {
        assertThatThrownBy(() -> new TwilioSmsSender(RestClient.builder(), "AC123", "token", "", "", false))
                .hasMessageContaining("TWILIO_TRIAL_TEMPLATE=sms_order_confirmation");
        assertThatThrownBy(() -> new TwilioSmsSender(RestClient.builder(), "AC123", "token", "", "order_confirmed", false))
                .hasMessageContaining("must be one of Twilio's trial templates");
    }

    private static MultiValueMap<String, String> form(String to, String from, String body) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", to);
        if (from != null) form.add("From", from);
        form.add("Body", body);
        return form;
    }
}
