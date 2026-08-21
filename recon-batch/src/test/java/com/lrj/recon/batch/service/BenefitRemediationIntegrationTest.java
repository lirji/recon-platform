package com.lrj.recon.batch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.recon.core.domain.model.RemediationAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BenefitRemediationIntegrationTest {
    @Autowired BenefitRemediationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MessageInboxStore inbox;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM remediation_command_outbox WHERE tenant_id='benefit-test'");
        jdbc.update("DELETE FROM remediation_suggestion WHERE tenant_id='benefit-test'");
    }

    @Test
    void approvedReissueProducesOneIdempotentCommandOutboxRow() throws Exception {
        var suggestion = service.propose(new BenefitRemediationService.ProposeCommand(
                "benefit-test", "ENTITLEMENT_FULFILLMENT", "DISC-1", "ITEM-1", "OP-1",
                RemediationAction.REISSUE, "provider fact explicitly proves not issued"));
        var approved = service.approve("benefit-test", suggestion.suggestionId(), "APPROVAL-1");

        assertThat(approved.status().name()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM remediation_command_outbox
                WHERE tenant_id='benefit-test' AND suggestion_id=?
                """, Integer.class, suggestion.suggestionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT payload FROM remediation_command_outbox
                WHERE tenant_id='benefit-test' AND suggestion_id=?
                """, String.class, suggestion.suggestionId()))
                .contains("\"originalOperationNo\":\"OP-1\"")
                .contains("\"approvalRef\":\"APPROVAL-1\"");

        jdbc.update("""
                UPDATE remediation_suggestion SET status='DISPATCHING',version=version+1
                WHERE tenant_id='benefit-test' AND suggestion_id=?
                """, suggestion.suggestionId());
        String commandId = "recon-remediation:" + suggestion.suggestionId();
        String result = """
                {"eventId":"RESULT-1","eventType":"REMEDIATION_RESULT","schemaVersion":"1.0",
                 "tenantId":"benefit-test","occurredAt":"2026-08-21T00:00:00Z","payload":{
                   "externalCommandId":"%s","remediationNo":"RM-1","status":"SUCCEEDED"}}
                """.formatted(commandId);
        var consumer = new BenefitRemediationResultConsumer(json, inbox, service,
                "recon-benefit-remediation-result-v1");
        consumer.consume(result);
        consumer.consume(result);

        assertThat(jdbc.queryForObject("""
                SELECT status FROM remediation_suggestion
                WHERE tenant_id='benefit-test' AND suggestion_id=?
                """, String.class, suggestion.suggestionId())).isEqualTo("SUCCEEDED");
    }

    @Test
    void dispatchedAuditEventIsAcknowledgedWithoutBeingTreatedAsTerminalResult() throws Exception {
        var suggestion = service.propose(new BenefitRemediationService.ProposeCommand(
                "benefit-test", "ENTITLEMENT_FULFILLMENT", "DISC-DISPATCH", "ITEM-1", "OP-1",
                RemediationAction.REISSUE, "provider proves not issued"));
        service.approve("benefit-test", suggestion.suggestionId(), "APPROVAL-DISPATCH");
        jdbc.update("UPDATE remediation_suggestion SET status='DISPATCHING',version=version+1 "
                        + "WHERE tenant_id='benefit-test' AND suggestion_id=?", suggestion.suggestionId());
        String commandId = "recon-remediation:" + suggestion.suggestionId();
        String event = """
                {"eventId":"DISPATCHED-1","eventType":"REMEDIATION_DISPATCHED","schemaVersion":"1.0",
                 "tenantId":"benefit-test","occurredAt":"2026-08-21T00:00:00Z","payload":{
                   "externalCommandId":"%s","remediationNo":"RM-1","status":"DISPATCHING"}}
                """.formatted(commandId);

        new BenefitRemediationResultConsumer(json, inbox, service,
                "recon-benefit-remediation-result-v1").consume(event);

        assertThat(jdbc.queryForObject("SELECT status FROM remediation_suggestion "
                        + "WHERE tenant_id='benefit-test' AND suggestion_id=?", String.class,
                suggestion.suggestionId())).isEqualTo("DISPATCHING");
    }

    @Test
    void reissueWithoutOriginalOperationIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.propose(new BenefitRemediationService.ProposeCommand(
                "benefit-test", "ENTITLEMENT_FULFILLMENT", "DISC-2", "ITEM-2", null,
                RemediationAction.REISSUE, "missing")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originalOperationNo");
    }
}
