package run.halo.comment.widget.captcha;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Ticker;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import org.altcha.altcha.v2.Altcha;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

class AltchaServiceTest {
    private String solve(Altcha.Challenge challenge) throws Exception {
        var solution = Altcha.solveChallenge(challenge, Altcha.kdf("PBKDF2/SHA-256"));
        var json = new ObjectMapper().writeValueAsString(new Altcha.Payload(challenge, solution));
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void verifiesOnceAndRejectsReplays() throws Exception {
        var service = new AltchaService();
        var token = solve(service.createChallenge().block());
        assertThat(service.verify(token).block()).isTrue();
        assertThat(service.verify(token).block()).isFalse();
    }

    @Test
    void concurrentSubmissionsOnlyConsumeTheChallengeOnce() throws Exception {
        var service = new AltchaService();
        var token = solve(service.createChallenge().block());
        var accepted = Flux.range(0, 8).flatMap(index -> service.verify(token)
            .subscribeOn(Schedulers.parallel())).filter(Boolean::booleanValue).count().block();
        assertThat(accepted).isEqualTo(1L);
    }

    @Test
    void rejectsTamperingWithoutConsumingTheOriginal() throws Exception {
        var service = new AltchaService();
        var challenge = service.createChallenge().block();
        var token = solve(challenge);
        var json = new ObjectMapper().readTree(Base64.getDecoder().decode(token));
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("challenge").path("parameters"))
            .put("cost", Integer.MAX_VALUE);
        var tampered = Base64.getEncoder().encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(service.verify(tampered).block()).isFalse();
        assertThat(service.verify(token).block()).isTrue();
    }

    @Test
    void rejectsExpiredAndUnknownChallenges() throws Exception {
        var nanos = new AtomicLong();
        var service = new AltchaService(new Ticker() {
            @Override
            public long read() {
                return nanos.get();
            }
        });
        var token = solve(service.createChallenge().block());
        assertThat(new AltchaService().verify(token).block()).isFalse();
        nanos.set(Duration.ofMinutes(6).toNanos());
        assertThat(service.verify(token).block()).isFalse();
    }

    @Test
    void rejectsMalformedOrOversizedPayloads() {
        var service = new AltchaService();
        for (var token : new String[]{"", "invalid", "e30=", "x".repeat(8193)}) {
            assertThat(service.verify(token).block()).isFalse();
        }
        assertThat(service.verify(null).block()).isFalse();
    }
}
