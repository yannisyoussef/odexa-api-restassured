package qa.odexa.model;

import java.time.Instant;
import java.util.UUID;

public record Order(
    UUID id,
    UUID productId,
    int quantity,
    long totalMinor,
    String currency,
    String status,
    long version,
    Instant createdAt) {}
