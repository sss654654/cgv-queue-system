package com.example.seats.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "theaters")
public class Theater {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String theaterId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int totalSeats = 300;

    protected Theater() {
    }

    public Theater(String theaterId, String name, int totalSeats) {
        this.theaterId = theaterId;
        this.name = name;
        this.totalSeats = totalSeats;
    }

    public Long getId() {
        return id;
    }

    public String getTheaterId() {
        return theaterId;
    }

    public String getName() {
        return name;
    }

    public int getTotalSeats() {
        return totalSeats;
    }
}
