package qa.odexa.config;

/** Environment-supplied credentials; never include this object's fields in diagnostics. */
public final class Credentials {
  private final String username;
  private final String password;

  public Credentials(String username, String password) {
    if (username == null || username.isBlank() || password == null || password.isBlank()) {
      throw new IllegalArgumentException("Credentials require username and password");
    }
    this.username = username;
    this.password = password;
  }

  public String username() {
    return username;
  }

  public String password() {
    return password;
  }

  @Override
  public String toString() {
    return "Credentials[username=<redacted>, password=<redacted>]";
  }
}
