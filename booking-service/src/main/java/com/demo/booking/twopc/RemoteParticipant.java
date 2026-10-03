package com.demo.booking.twopc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import com.demo.booking.util.Timer;

import java.util.Map;

/**
 * A participant reached over HTTP.
 */
public class RemoteParticipant {
    private static final Logger logger = LoggerFactory.getLogger(RemoteParticipant.class);

    private final String name;
    private final String baseUrl;
    private final RestTemplate rest;

    public RemoteParticipant(String name, String baseUrl, RestTemplate rest) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.rest = rest;
    }

    public String name() {
        return name;
    }

    public boolean prepare(Map<String, Object> request) {
        Timer timer = new Timer();
        try {
            Map<?, ?> response = rest.postForObject(baseUrl + "/xa/prepare", request, Map.class);
            boolean yes = response != null && "YES".equals(response.get("vote"));
            logger.info("{} voted {} in {}ms", name, yes ? "YES" : "NO", timer.elapsedMillis());
            return yes;
        } catch (RuntimeException e) {
            logger.warn("{} did not answer prepare, counting it as NO", name);
            return false;
        }
    }

    public void commit(String transactionId) {
        send("/xa/commit", transactionId);
    }

    public void rollback(String transactionId) {
        send("/xa/rollback", transactionId);
    }

    public Object inDoubt() {
        try {
            return rest.getForObject(baseUrl + "/xa/in-doubt", Map.class);
        } catch (RuntimeException e) {
            return Map.of("error", "unreachable");
        }
    }

    private void send(String path, String transactionId) {
        try {
            rest.postForObject(baseUrl + path, Map.of("transactionId", transactionId), Map.class);
        } catch (RuntimeException e) {
            logger.error("{} did not accept {}, it has to be retried", name, path);
        }
    }
}
