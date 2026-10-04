package com.github.xingjianluo.riskengine;

import com.github.xingjianluo.riskengine.model.TransactionEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST API 冒烟测试：摄入 -> 评分 -> 告警 -> 规则更新 全链路。
 */
@SpringBootTest(classes = com.github.xingjianluo.onchainriskenginejava.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(ApiSmokeTest.class);
    private static final String WHALE = "0x" + "aa".repeat(20);

    @Autowired
    private TestRestTemplate rest;

    @Test
    void endToEndFlow() {
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 1; i <= 6; i++) {
            TransactionEvent event = new TransactionEvent(
                    "api-evt-" + i, "0xtxapi" + i, WHALE,
                    "0x" + String.format("%040x", i),
                    new BigDecimal("50000"), "ETH", base.plusSeconds(i * 60L));
            ResponseEntity<Map> response = rest.postForEntity("/api/events", event, Map.class);
            log.info("POST /api/events {} -> {}", event.eventId(), response.getBody());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("accepted", true);
        }

        // 重复投递同一事件 -> DUPLICATE_EVENT
        TransactionEvent dup = new TransactionEvent(
                "api-evt-1", "0xtxapi1", WHALE,
                "0x" + String.format("%040x", 1),
                new BigDecimal("50000"), "ETH", base.plusSeconds(60));
        ResponseEntity<Map> dupResponse = rest.postForEntity("/api/events", dup, Map.class);
        log.info("duplicate -> {}", dupResponse.getBody());
        assertThat(dupResponse.getBody()).containsEntry("rejectionReason", "DUPLICATE_EVENT");

        ResponseEntity<Map> score = rest.getForEntity("/api/scores/" + WHALE, Map.class);
        log.info("GET /api/scores -> {}", score.getBody());
        assertThat(score.getBody()).containsEntry("score", 100);

        ResponseEntity<Object[]> alerts = rest.getForEntity("/api/alerts", Object[].class);
        log.info("GET /api/alerts -> {}", (Object[]) alerts.getBody());
        assertThat(alerts.getBody()).isNotEmpty();

        Map<String, Object> newRules = Map.of(
                "version", 2,
                "largeAmountThreshold", 100000,
                "fanOutThreshold", 5,
                "fanInThreshold", 5,
                "largeAmountWeight", 10,
                "fanOutWeight", 40,
                "fanInWeight", 10,
                "alertScoreThreshold", 50);
        ResponseEntity<Map> updated = rest.exchange("/api/rules", HttpMethod.PUT,
                new HttpEntity<>(newRules), Map.class);
        log.info("PUT /api/rules -> {}", updated.getBody());
        assertThat(updated.getBody()).containsEntry("version", 2);

        // 同一版本号重复发布 -> 409，已发布内容不被覆盖
        ResponseEntity<Map> duplicate = rest.exchange("/api/rules", HttpMethod.PUT,
                new HttpEntity<>(newRules), Map.class);
        log.info("PUT /api/rules duplicate -> {} {}", duplicate.getStatusCode(), duplicate.getBody());
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).containsEntry("error", "DUPLICATE_RULE_VERSION");

        // 版本历史可查询，历史版本可复算
        ResponseEntity<Object[]> versions = rest.getForEntity("/api/rules/versions", Object[].class);
        log.info("GET /api/rules/versions -> {}", (Object[]) versions.getBody());
        assertThat(versions.getBody()).containsExactly(1, 2);

        ResponseEntity<Map> replayV1 = rest.getForEntity("/api/scores/" + WHALE + "?version=1", Map.class);
        log.info("GET /api/scores?version=1 -> {}", replayV1.getBody());
        assertThat(replayV1.getBody()).containsEntry("ruleSetVersion", 1)
                .containsEntry("score", 100);

        ResponseEntity<Map> unknownVersion = rest.getForEntity("/api/scores/" + WHALE + "?version=99", Map.class);
        log.info("GET /api/scores?version=99 -> {} {}", unknownVersion.getStatusCode(), unknownVersion.getBody());
        assertThat(unknownVersion.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 兼容性：旧字段（score/ruleSetVersion/evidence）仍在，新增结构化字段为附加内容
        ResponseEntity<Map> scoreNow = rest.getForEntity("/api/scores/" + WHALE, Map.class);
        log.info("GET /api/scores -> {}", scoreNow.getBody());
        assertThat(scoreNow.getBody()).containsKeys(
                "address", "score", "ruleSetVersion", "evidence", "ruleHits", "eventSetFingerprint");

        ResponseEntity<Map> invalidScore = rest.getForEntity("/api/scores/not-an-address", Map.class);
        log.info("GET /api/scores/not-an-address -> {}", invalidScore.getBody());
        assertThat(invalidScore.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
