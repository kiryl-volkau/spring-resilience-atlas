package fx.api;
public interface Paths {
    String API = "/api";
    String V1 = API + "/v1";
    class Orders { public static final String BASE = V1 + "/orders"; public static final String BY_ID = "/{id}"; }
    class Users  { public static final String BASE = V1 + "/users";  public static final String BY_ID = "/{userId}"; }
}
