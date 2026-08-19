package com.lrj.recon.batch.alert;

import com.lrj.recon.core.domain.model.AlertOutbox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * A2 · {@link WebhookAlertDispatcher} 单测(MockRestServiceServer 绑定 RestClient,无真实网络):
 * 2xx → SENT(true)+计量 sent;5xx → 失败(false)+计量 failed(交中继补投);幂等键与可选鉴权头正确下发。
 */
class WebhookAlertDispatcherTest {

    private static final String URL = "https://hooks.example.test/recon-alert";

    private static AlertOutbox entry() {
        return AlertOutbox.builder()
                .id("outbox-1").runId("run-1").fingerprint("fp-abc")
                .payload("{\"type\":\"MISSING\",\"amount\":\"100\"}")
                .idempotencyKey("idem-1").attempt(0)
                .build();
    }

    private static AlertWebhookProperties props(String headerName, String headerValue) {
        AlertWebhookProperties p = new AlertWebhookProperties();
        p.setUrl(URL);
        p.setHeaderName(headerName);
        p.setHeaderValue(headerValue);
        return p;
    }

    @Test
    void success2xxReturnsTruePostsEnvelopeWithIdempotencyKeyAndAuthHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WebhookAlertDispatcher dispatcher =
                new WebhookAlertDispatcher(builder.build(), props("Authorization", "Bearer secret"), meters);

        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Idempotency-Key", "idem-1"))
                .andExpect(header("Authorization", "Bearer secret"))
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.fingerprint").value("fp-abc"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andRespond(withSuccess());

        assertThat(dispatcher.dispatch(entry())).isTrue();
        server.verify();
        assertThat(meters.counter("recon.alert.dispatch", "channel", "webhook", "outcome", "sent").count())
                .isEqualTo(1.0);
    }

    @Test
    void serverError5xxReturnsFalseForRetry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WebhookAlertDispatcher dispatcher =
                new WebhookAlertDispatcher(builder.build(), props(null, null), meters);

        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThat(dispatcher.dispatch(entry())).isFalse();
        server.verify();
        assertThat(meters.counter("recon.alert.dispatch", "channel", "webhook", "outcome", "failed").count())
                .isEqualTo(1.0);
    }
}
