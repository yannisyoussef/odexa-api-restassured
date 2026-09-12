package qa.odexa.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import qa.odexa.config.Actor;
import qa.odexa.config.Credentials;
import qa.odexa.config.TargetConfig;

/** Separate, non-logging OIDC channel. No Allure filters or authentication-response attachments. */
public final class AuthClient {
  private static final int MAX_RESPONSE_BYTES = 64 * 1024;

  private final TargetConfig config;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  public AuthClient(TargetConfig config) {
    this.config = config;
    http =
        HttpClient.newBuilder()
            .connectTimeout(config.requestTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  Token authenticate(Credentials credentials, Actor actor) {
    return grant(
        "grant_type=password&username="
            + encode(credentials.username())
            + "&password="
            + encode(credentials.password()),
        actor);
  }

  Token refresh(String refreshToken, Actor actor) {
    Token replacement =
        grant("grant_type=refresh_token&refresh_token=" + encode(refreshToken), actor);
    // OIDC may omit refresh_token when it has not rotated; retain the existing refresh token.
    return replacement.refreshToken == null
        ? new Token(replacement.accessToken, refreshToken, replacement.expiresIn)
        : replacement;
  }

  private Token grant(String form, Actor actor) {
    int status = 0;
    CompletableFuture<HttpResponse<String>> pending = null;
    try {
      long deadline = System.nanoTime() + config.requestTimeout().toNanos();
      String body = form + "&client_id=" + encode(config.clientId());
      if (config.clientSecret() != null && !config.clientSecret().isEmpty()) {
        body += "&client_secret=" + encode(config.clientSecret());
      }
      HttpRequest request =
          HttpRequest.newBuilder(config.tokenUri())
              .timeout(config.requestTimeout())
              .header("Accept", "application/json")
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      pending =
          http.sendAsync(request, info -> new BoundedBodySubscriber(actor, info.statusCode()));
      // HttpRequest.timeout alone does not bound a stalled response body on JDK 25.
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        throw new TimeoutException();
      }
      HttpResponse<String> response = pending.get(remaining, TimeUnit.NANOSECONDS);
      status = response.statusCode();
      if (status != 200) {
        throw new AuthException(actor, status, classify(response.body()));
      }
      JsonNode json;
      try {
        json = mapper.readTree(response.body());
      } catch (Exception ignored) {
        throw new AuthException(actor, status, AuthException.Classification.INVALID_RESPONSE);
      }
      if (json == null
          || !json.isObject()
          || !json.path("access_token").isTextual()
          || !json.path("token_type").asText().equalsIgnoreCase("Bearer")
          || !json.path("expires_in").isIntegralNumber()
          || !json.path("expires_in").canConvertToInt()
          || json.path("expires_in").intValue() <= 0) {
        throw new AuthException(actor, status, AuthException.Classification.INVALID_RESPONSE);
      }
      String access = json.path("access_token").textValue();
      if (!access.matches("[A-Za-z0-9._~+/-]+=*")) {
        throw new AuthException(actor, status, AuthException.Classification.INVALID_RESPONSE);
      }
      JsonNode refresh = json.get("refresh_token");
      if (refresh != null
          && !refresh.isNull()
          && (!refresh.isTextual() || refresh.textValue().isBlank())) {
        throw new AuthException(actor, status, AuthException.Classification.INVALID_RESPONSE);
      }
      return new Token(
          access,
          refresh == null || refresh.isNull() ? null : refresh.textValue(),
          json.path("expires_in").intValue());
    } catch (AuthException safe) {
      throw safe;
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
      throw new AuthException(actor, status, AuthException.Classification.INTERRUPTED);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof AuthException safe) {
        throw safe;
      }
      throw new AuthException(actor, status, AuthException.Classification.TRANSPORT);
    } catch (Exception ignored) {
      throw new AuthException(actor, status, AuthException.Classification.TRANSPORT);
    } finally {
      if (pending != null) {
        // Cancel the exchange on expiry/interruption; completed exchanges are unaffected.
        pending.cancel(true);
      }
    }
  }

  private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<String> {
    private final Actor actor;
    private final int status;
    private final ByteBuffer bytes = ByteBuffer.allocate(MAX_RESPONSE_BYTES);
    private final CompletableFuture<String> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    private BoundedBodySubscriber(Actor actor, int status) {
      this.actor = actor;
      this.status = status;
    }

    @Override
    public CompletionStage<String> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription incoming) {
      if (subscription != null || body.isDone()) {
        incoming.cancel();
        return;
      }
      subscription = incoming;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
      if (body.isDone()) {
        return;
      }
      for (ByteBuffer item : items) {
        if (item.remaining() > bytes.remaining()) {
          body.completeExceptionally(
              new AuthException(actor, status, AuthException.Classification.INVALID_RESPONSE));
          subscription.cancel();
          return;
        }
        bytes.put(item);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable ignored) {
      body.completeExceptionally(
          new AuthException(actor, status, AuthException.Classification.TRANSPORT));
    }

    @Override
    public void onComplete() {
      if (!body.isDone()) {
        bytes.flip();
        body.complete(StandardCharsets.UTF_8.decode(bytes).toString());
      }
    }
  }

  private AuthException.Classification classify(String body) {
    try {
      JsonNode json = mapper.readTree(body);
      String error = json == null ? "" : json.path("error").asText();
      return switch (error) {
        case "invalid_grant" -> AuthException.Classification.INVALID_GRANT;
        case "invalid_client" -> AuthException.Classification.INVALID_CLIENT;
        default -> AuthException.Classification.HTTP_FAILURE;
      };
    } catch (Exception ignored) {
      return AuthException.Classification.HTTP_FAILURE;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  static final class Token {
    final String accessToken;
    final String refreshToken;
    final int expiresIn;

    private Token(String accessToken, String refreshToken, int expiresIn) {
      this.accessToken = accessToken;
      this.refreshToken = refreshToken;
      this.expiresIn = expiresIn;
    }

    @Override
    public String toString() {
      return "Token[<redacted>]";
    }
  }

  @Override
  public String toString() {
    return "AuthClient[OIDC]";
  }
}
