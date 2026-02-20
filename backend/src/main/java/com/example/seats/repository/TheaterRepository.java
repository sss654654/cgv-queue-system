package com.example.seats.repository;

import com.example.seats.entity.Theater;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TheaterRepository extends JpaRepository<Theater, Long> {

    Optional<Theater> findByTheaterId(String theaterId);
}
