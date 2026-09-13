package qa.odexa.model;

import java.time.Instant;
import java.util.UUID;

public record Payment(
    UUID id,
    UUID orderId,
    long amountMinor,
    String currency,
    String status,
    Instant createdAt,
    Instant updatedAt) {}
