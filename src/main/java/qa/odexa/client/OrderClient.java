package qa.odexa.client;

import io.restassured.http.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import qa.odexa.auth.AuthenticatedSession;
import qa.odexa.http.ApiHttp;
import qa.odexa.http.ApiResponse;

/** Gateway operations without business assertions or automatic write retries. */
public final class OrderClient {
  private final ApiHttp http;
  private final AuthenticatedSession session;
  private CreationJournal journal;
  private boolean creationBlocked;

  public OrderClient(ApiHttp http, AuthenticatedSession session) {
    this.http = Objects.requireNonNull(http, "http");
    this.session = session;
  }

  public ApiResponse create(Object body, String key) {
    CreationJournal active;
    synchronized (this) {
      if (creationBlocked) {
        throw new IllegalStateException("Order client is blocked after uncertain fixture cleanup");
      }
      active = journal;
      if (active != null) {
        if (active.sealed) {
          throw new IllegalStateException("Order creation is closed during fixture cleanup");
        }
        active.inFlight++;
      }
    }
    ApiResponse response = null;
    try {
      response =
          http.execute(
              session,
              Method.POST,
              "/api/v1/orders",
              Map.of(),
              key == null ? Map.of() : Map.of("Idempotency-Key", key),
              body);
      return response;
    } finally {
      if (active != null) {
        synchronized (this) {
          active.inFlight--;
          if (response == null) {
            active.uncertain = true;
          } else {
            active.responses.add(response);
          }
        }
      }
    }
  }

  public ApiResponse get(UUID id) {
    return http.execute(
        session,
        Method.GET,
        "/api/v1/orders/" + Objects.requireNonNull(id, "id"),
        Map.of(),
        Map.of(),
        null);
  }

  /**
   * Per-client, per-fixture transport journal. It captures even a response that a failing test
   * never reaches track() for. Use only this client for checkout while its fixture is acquired.
   */
  public synchronized CreationJournal openCreationJournal() {
    if (creationBlocked) {
      throw new IllegalStateException("Order client is blocked after uncertain fixture cleanup");
    }
    if (journal != null) {
      throw new IllegalStateException("An order creation journal is already active");
    }
    journal = new CreationJournal();
    return journal;
  }

  public final class CreationJournal implements AutoCloseable {
    private final List<ApiResponse> responses = new ArrayList<>();
    private boolean uncertain;
    private boolean sealed;
    private int inFlight;

    private CreationJournal() {}

    /**
     * Freeze new POSTs and snapshot completed responses, including those not explicitly tracked.
     */
    public List<ApiResponse> seal() {
      synchronized (OrderClient.this) {
        sealed = true;
        // A checkout completing after this snapshot is not represented in cleanup's response set.
        // Latch uncertainty atomically, even if it finishes before the next safety check.
        uncertain |= inFlight != 0;
        return List.copyOf(responses);
      }
    }

    /** An incomplete or ambiguous request makes stock restoration unsafe. */
    public boolean hasUncertainRequests() {
      synchronized (OrderClient.this) {
        return uncertain || inFlight != 0;
      }
    }

    /** Fail closed for this client after unsafe cleanup; there is deliberately no reset method. */
    public void abandon() {
      synchronized (OrderClient.this) {
        sealed = true;
        uncertain = true;
        creationBlocked = true;
      }
    }

    @Override
    public void close() {
      synchronized (OrderClient.this) {
        sealed = true;
        creationBlocked |= uncertain || inFlight != 0;
        if (journal == this) {
          journal = null;
        }
        responses.clear();
      }
    }
  }
}
