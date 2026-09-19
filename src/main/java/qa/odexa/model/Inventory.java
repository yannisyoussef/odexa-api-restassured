package qa.odexa.model;

import java.util.UUID;

public record Inventory(UUID productId, long onHand, long reserved, long available, long version) {}
