package com.jb.it;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Through a real Tomcat, where sendError() is forwarded to /error (MockMvc skips that step).
 * API callers must get the real status, never a redirect to the sign-in page whose HTML the
 * frontend would read as a successful, empty response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpErrorStatusIT extends IntegrationTestBase {
    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpResponse<String> send(HttpRequest.Builder req) throws Exception {
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    }

    @Test
    void counterStaffGet403NotALoginRedirect() throws Exception {
        var res = send(get("/api/admin/dashboard").header("Authorization", "Bearer " + jwtService.issue(counter)));
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.headers().firstValue("Location")).isEmpty();
    }

    @Test
    void signedOutApiCallsGet401NotALoginRedirect() throws Exception {
        var res = send(get("/api/admin/dashboard"));
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().firstValue("Location")).isEmpty();
    }

    @Test
    void expiredSessionWritesAreRefusedWith401() throws Exception {
        var res = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/settings"))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + CSRF)
                .header("X-XSRF-TOKEN", CSRF)
                .POST(HttpRequest.BodyPublishers.ofString("[{\"key\":\"title\",\"value\":\"x\"}]")));
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(settingsService.get("title", "en")).isNotEqualTo("x");
    }

    @Test
    void adminsStillGetData() throws Exception {
        var res = send(get("/api/admin/dashboard").header("Authorization", "Bearer " + jwtService.issue(admin)));
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("totalBookings");
    }

    @Test
    void googleSignInStillRedirectsToGoogle() throws Exception {
        var res = send(get("/oauth2/authorization/google"));
        assertThat(res.statusCode()).isEqualTo(302);
        assertThat(res.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).contains("accounts.google.com"));
    }
}
