package com.example.ArtConnect.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ArtConnect.model.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByBuyerIdOrderByCreatedAtDesc(Long buyerId);

    List<Order> findByArtistIdOrderByCreatedAtDesc(Long artistId);
}
