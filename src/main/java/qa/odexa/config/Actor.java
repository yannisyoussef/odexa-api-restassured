package qa.odexa.config;

/** Only identities provisioned by the public v0.1.0 fixture contract. */
public enum Actor {
  CUSTOMER_A,
  CUSTOMER_B,
  OTHER_CUSTOMER_A,
  MERCHANT_A;

  public String role() {
    return this == MERCHANT_A ? "MERCHANT_ADMIN" : "CUSTOMER";
  }
}
