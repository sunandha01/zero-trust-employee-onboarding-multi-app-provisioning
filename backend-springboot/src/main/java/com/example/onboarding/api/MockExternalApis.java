package com.example.onboarding.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock external systems called by the Camunda REST Outbound Connector.
 * They stand in for a real IAM (GitHub/Keycloak), a background-verification vendor,
 * a courier company and Slack. Each protected endpoint checks the secret that the
 * connector sends, so a wrong or missing secret returns 401.
 */
@RestController
public class MockExternalApis {

  private static final Logger LOG = LoggerFactory.getLogger(MockExternalApis.class);

  private final String iamToken;
  private final String bgvApiKey;
  private final String slackToken;
  private final AtomicInteger sequence = new AtomicInteger(1);
  private final Map<String, Map<String, Object>> accounts = new ConcurrentHashMap<>();

  public MockExternalApis(
      @Value("${mock.iam.token}") String iamToken,
      @Value("${mock.bgv.api-key}") String bgvApiKey,
      @Value("${mock.slack.token}") String slackToken) {
    this.iamToken = iamToken;
    this.bgvApiKey = bgvApiKey;
    this.slackToken = slackToken;
  }

  /** Mock IAM: creates one user account in one SaaS application. Bearer token required. */
  @PostMapping("/api/iam/accounts")
  public ResponseEntity<Map<String, Object>> createAccount(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestBody Map<String, Object> request) {
    if (!("Bearer " + iamToken).equals(auth)) return unauthorized("IAM");
    String app = String.valueOf(request.get("app"));
    String username = String.valueOf(request.get("username"));
    String accountId = app + "-" + username + "-" + sequence.getAndIncrement();
    Map<String, Object> account = Map.of(
        "accountId", accountId, "app", app, "username", username,
        "employeeId", String.valueOf(request.get("employeeId")), "status", "ACTIVE");
    accounts.put(accountId, account);
    LOG.info("IAM: created {}", accountId);
    return ResponseEntity.status(HttpStatus.CREATED).body(account);
  }

  /** Lists the accounts created for an employee (useful to check provisioning). */
  @GetMapping("/api/iam/accounts")
  public List<Map<String, Object>> listAccounts(@RequestParam String employeeId) {
    return accounts.values().stream().filter(a -> employeeId.equals(a.get("employeeId"))).toList();
  }

  /** Mock background-verification vendor. API key required. EMP-9xxx is always flagged. */
  @PostMapping("/api/background-checks")
  public ResponseEntity<Map<String, Object>> backgroundCheck(
      @RequestHeader(value = "X-API-Key", required = false) String apiKey,
      @RequestBody Map<String, Object> request) {
    if (!bgvApiKey.equals(apiKey)) return unauthorized("background-check");
    String employeeId = String.valueOf(request.get("employeeId"));
    String status = employeeId.startsWith("EMP-9") ? "FLAGGED" : "CLEAR";
    LOG.info("Background check {} -> {}", employeeId, status);
    return ResponseEntity.ok(Map.of(
        "caseId", "BGV-" + employeeId.replace("EMP-", ""), "employeeId", employeeId, "status", status));
  }

  /** Mock courier: books a shipment for a reserved laptop. */
  @PostMapping("/api/courier/shipments")
  public ResponseEntity<Map<String, Object>> bookShipment(@RequestBody Map<String, Object> request) {
    String trackingId = "TRK-" + String.valueOf(request.get("assetTag")).replace("AST-", "");
    LOG.info("Courier: shipment {} to {}", trackingId, request.get("shippingAddress"));
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
        "trackingId", trackingId, "courier", "MockExpress", "status", "DISPATCHED"));
  }

  /** Mock Slack chat.postMessage. Bot token required. */
  @PostMapping("/api/slack/messages")
  public ResponseEntity<Map<String, Object>> postMessage(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestBody Map<String, Object> request) {
    if (!("Bearer " + slackToken).equals(auth)) return unauthorized("Slack");
    LOG.info("Slack {}: {}", request.get("channel"), request.get("text"));
    return ResponseEntity.ok(Map.of(
        "ok", true, "channel", String.valueOf(request.get("channel")), "ts", String.valueOf(Instant.now().toEpochMilli())));
  }

  private static ResponseEntity<Map<String, Object>> unauthorized(String system) {
    LOG.warn("{}: rejected request with missing or wrong secret", system);
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid credentials"));
  }
}
