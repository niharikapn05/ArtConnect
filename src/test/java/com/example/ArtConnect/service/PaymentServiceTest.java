package com.example.ArtConnect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.example.ArtConnect.exception.ApiException;

class PaymentServiceTest {

    @Test
    void normalizesTheWaysPeopleTypeIndianMobileNumbers() {
        for (String typed : new String[] { "9876543210", "98765 43210", "+91 98765 43210", "+91-9876543210",
                "919876543210", "09876543210" }) {
            assertThat(PaymentService.normalizeIndianMobile(typed)).as(typed).isEqualTo("+919876543210");
        }
    }

    @Test
    void rejectsNumbersThatAreNotIndianMobiles() {
        for (String typed : new String[] { "5876543210", "987654321", "98765432101", "+1 415 555 0100" }) {
            assertThatThrownBy(() -> PaymentService.normalizeIndianMobile(typed)).as(typed).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void convertsRupeesToPaise() {
        assertThat(PaymentService.toSmallestUnit(new BigDecimal("4200.00"))).isEqualTo(420000);
        assertThat(PaymentService.toSmallestUnit(new BigDecimal("1499.99"))).isEqualTo(149999);
    }
}
