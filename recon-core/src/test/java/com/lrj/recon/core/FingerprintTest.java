package com.lrj.recon.core;

import com.lrj.recon.core.domain.service.Fingerprint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintTest {

    @Test
    void idempotent_same_inputs_same_hash() {
        String a = Fingerprint.of("scn", "2026-08-17", "SEG1", "AMOUNT_MISMATCH", "G1", "K1", null);
        String b = Fingerprint.of("scn", "2026-08-17", "SEG1", "AMOUNT_MISMATCH", "G1", "K1", null);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void sha256_hex_length_64() {
        String fp = Fingerprint.of("scn", "2026-08-17", "SEG1", "MISSING", "G1", "K1", null);
        assertThat(fp).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void stable_for_null_keys_via_empty_token() {
        // BRIDGE_BROKEN / CURRENCY_MISMATCH 等空键类型仍稳定幂等
        String withNulls = Fingerprint.of("scn", "2026-08-17", "SEG1", "BRIDGE_BROKEN", null, null, "SEG1");
        String again = Fingerprint.of("scn", "2026-08-17", "SEG1", "BRIDGE_BROKEN", null, null, "SEG1");
        assertThat(withNulls).isEqualTo(again);

        // 显式传入 '∅' 与传 null 折叠结果一致 (coalesce 语义)
        String explicit = Fingerprint.of("scn", "2026-08-17", "SEG1", "BRIDGE_BROKEN",
                Fingerprint.NULL_TOKEN, Fingerprint.NULL_TOKEN, "SEG1");
        assertThat(explicit).isEqualTo(withNulls);
    }

    @Test
    void different_type_changes_fingerprint() {
        String missing = Fingerprint.of("scn", "2026-08-17", "SEG1", "MISSING", "G1", "K1", null);
        String amount = Fingerprint.of("scn", "2026-08-17", "SEG1", "AMOUNT_MISMATCH", "G1", "K1", null);
        assertThat(missing).isNotEqualTo(amount);
    }

    @Test
    void different_bridge_stage_changes_fingerprint() {
        String seg1 = Fingerprint.of("scn", "2026-08-17", "SEG1", "BRIDGE_BROKEN", null, null, "SEG1");
        String seg2 = Fingerprint.of("scn", "2026-08-17", "SEG2", "BRIDGE_BROKEN", null, null, "SEG2");
        assertThat(seg1).isNotEqualTo(seg2);
    }
}
