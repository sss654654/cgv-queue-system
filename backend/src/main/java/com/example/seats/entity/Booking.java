package com.example.seats.entity;

import com.example.seats.converter.JsonListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 64)
    private String bookingId;

    @Column(nullable = false)
    private String movieId;

    @Column(nullable = false, length = 50)
    private String theaterId;

    @Convert(converter = JsonListConverter.class)
    @Column(columnDefinition = "JSON", nullable = false)
    private List<String> seats;

    @Column(nullable = false)
    private int totalPrice;

    @Column(nullable = false, length = 64)
    private String requestId;

    @Column(nullable = false)
    private LocalDateTime bookedAt = LocalDateTime.now();

    protected Booking() {
    }

    public Booking(String bookingId, String movieId, String theaterId,
                   List<String> seats, int totalPrice, String requestId) {
        this.bookingId = bookingId;
        this.movieId = movieId;
        this.theaterId = theaterId;
        this.seats = seats;
        this.totalPrice = totalPrice;
        this.requestId = requestId;
        this.bookedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getBookingId() {
        return bookingId;
    }

    public String getMovieId() {
        return movieId;
    }

    public String getTheaterId() {
        return theaterId;
    }

    public List<String> getSeats() {
        return seats;
    }

    public int getTotalPrice() {
        return totalPrice;
    }

    public String getRequestId() {
        return requestId;
    }

    public LocalDateTime getBookedAt() {
        return bookedAt;
    }
}
