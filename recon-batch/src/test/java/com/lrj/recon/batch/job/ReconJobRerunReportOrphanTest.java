package com.lrj.recon.batch.job;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #5 重跑清孤儿报表行 (ADR-7 + #5): 重跑时某 (segment, currency) 的数据整体消失, 旧报表行必须被
 * {@code cleanBounded} 分批删掉, 不残留陈旧金额。否则 reportStep 只对本次仍产出的组 upsert, 消失组的旧行成孤儿。
 *
 * <p>首跑含 USD + EUR 两币种干净匹配 → 两行报表; 删掉 EUR 源数据后重跑 → 只应剩 USD 一行, EUR 旧行不残留。
 */
class ReconJobRerunReportOrphanTest extends AbstractReconJobIT {

    private static final String RUN = "run-report-orphan";

    @Test
    void rerunRemovesOrphanReportRowForVanishedCurrency() throws Exception {
        // ---- 首跑: USD + EUR 各一组干净匹配 ----
        marketing("m-usd", "I-USD", "USD", 1000, "ISSUE", "PAID", BIZ);
        accounting("a-usd", "I-USD", "USD", 1000, "ISSUE", "PAID", BIZ);
        marketing("m-eur", "I-EUR", "EUR", 500, "ISSUE", "PAID", BIZ);
        accounting("a-eur", "I-EUR", "EUR", 500, "ISSUE", "PAID", BIZ);

        JobExecution first = launch(RUN, 1);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("recon_report", RUN)).isEqualTo(2L); // USD + EUR
        assertThat(reportCurrencies()).containsExactlyInAnyOrder("USD", "EUR");

        // ---- EUR 源数据整体消失 (账期数据变化), 重跑 ----
        jdbc.update("DELETE FROM recon_src_marketing WHERE ccy = 'EUR'");
        jdbc.update("DELETE FROM recon_src_accounting WHERE ccy = 'EUR'");

        JobExecution second = launch(RUN, 2);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 只剩 USD 一行, EUR 旧报表行被清 (无孤儿陈旧金额)
        assertThat(count("recon_report", RUN)).isEqualTo(1L);
        assertThat(reportCurrencies()).containsExactly("USD");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report WHERE run_id=? AND currency='EUR'", Long.class, RUN))
                .isZero();
        // USD 报表仍闭合
        assertThat(jdbc.queryForObject(
                "SELECT balanced FROM recon_report WHERE run_id=? AND currency='USD'", Integer.class, RUN))
                .isEqualTo(1);
    }

    private java.util.List<String> reportCurrencies() {
        return jdbc.queryForList("SELECT currency FROM recon_report WHERE run_id=? ORDER BY currency",
                String.class, RUN);
    }
}
