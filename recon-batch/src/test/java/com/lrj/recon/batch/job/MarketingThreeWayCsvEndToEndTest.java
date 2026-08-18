package com.lrj.recon.batch.job;

import com.lrj.recon.batch.service.ManualClearingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** M6 CSV→三方两段 Batch→处理链→报表的完整验收。 */
class MarketingThreeWayCsvEndToEndTest extends AbstractThreeWayJobIT {

    private static final CsvFiles CSV = CsvFiles.create();

    @DynamicPropertySource
    static void csvProperties(DynamicPropertyRegistry registry) {
        registry.add("recon.m4.source-type", () -> "csv-file");
        registry.add("recon.m4.marketing-file", () -> CSV.marketing.toString());
        registry.add("recon.m4.accounting-file", () -> CSV.accounting.toString());
        registry.add("recon.m4.channel-file", () -> CSV.channel.toString());
        registry.add("recon.m4.csv.charset", () -> "UTF-8");
        registry.add("recon.m4.csv.delimiter", () -> ",");
    }

    @Autowired
    private ManualClearingService manualClearing;

    @Test
    void csvFullChainReproducesAllMvpTypesPersistsRejectsAndConserves() throws Exception {
        String run = "run-m6-csv-matrix";

        JobExecution execution = launch(run, 1);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT type FROM discrepancy WHERE run_id=? ORDER BY type", String.class, run))
                .containsExactlyInAnyOrder(
                        "AMOUNT_MISMATCH", "MISSING", "DUPLICATE", "EXTRA", "BRIDGE_BROKEN",
                        "GROUP_SUM_MISMATCH", "CURRENCY_MISMATCH", "STATUS_MISMATCH", "TIMING");
        assertThat(count("discrepancy", run)).isEqualTo(10L);

        // 两段断链都可定位，且不退化为 MISSING。
        assertThat(jdbc.queryForList(
                "SELECT bridge_break_stage FROM discrepancy WHERE run_id=? AND type='BRIDGE_BROKEN'"
                        + " ORDER BY bridge_break_stage", String.class, run))
                .containsExactly("SEG1", "SEG2");

        // CSV 业务畸形行不终止整流；reject 保留文件和物理行号，合法尾部数据仍被处理。
        assertThat(count("recon_record_reject", run)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT raw_ref FROM recon_record_reject WHERE run_id=?", String.class, run))
                .contains("marketing.csv:");
        assertThat(jdbc.queryForObject(
                "SELECT reason FROM recon_record_reject WHERE run_id=?", String.class, run))
                .contains("not a signed long");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_record WHERE run_id=? AND raw_ref LIKE ?", Long.class,
                run, CSV.marketing.toString() + ":%"))
                .isGreaterThan(0L);

        // 跨币差异产生多币种报表；所有 segment/currency 桶双向守恒精确闭合。
        assertThat(count("recon_report", run)).isGreaterThanOrEqualTo(4L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report WHERE run_id=? AND balanced=0", Long.class, run)).isZero();
        assertThat(jdbc.queryForList(
                "SELECT left_residual_minor + right_residual_minor FROM recon_report WHERE run_id=?",
                Long.class, run)).allMatch(residual -> residual == 0L);
        assertThat(runStatus(run)).isEqualTo("COMPLETED");
    }

    @Test
    void csvRerunIsIdempotentAndPreservesManualDispositionAndSuggestion() throws Exception {
        String run = "run-m6-csv-rerun";
        assertThat(launch(run, 1).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        String discrepancyId = jdbc.queryForObject(
                "SELECT discrepancy_id FROM discrepancy WHERE run_id=? AND type='AMOUNT_MISMATCH'",
                String.class, run);
        String fingerprint = jdbc.queryForObject(
                "SELECT fingerprint FROM discrepancy WHERE discrepancy_id=?", String.class, discrepancyId);
        manualClearing.resolve(discrepancyId, "m6-ops", "CSV checked", null);

        long initialDiscrepancies = count("discrepancy", run);
        long initialRecords = count("recon_record", run);
        long initialSuggestions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reversal_suggestion WHERE fingerprint=?", Long.class, fingerprint);
        assertThat(initialSuggestions).isEqualTo(1L);
        assertThat(count("recon_record_reject", run)).isEqualTo(1L);

        assertThat(launch(run, 2).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(count("discrepancy", run)).isEqualTo(initialDiscrepancies);
        assertThat(count("recon_record", run)).isEqualTo(initialRecords);
        assertThat(count("recon_record_reject", run)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT fingerprint) FROM discrepancy WHERE run_id=?", Long.class, run))
                .isEqualTo(initialDiscrepancies);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM discrepancy_disposition WHERE fingerprint=?", String.class, fingerprint))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject(
                "SELECT operator FROM discrepancy_disposition WHERE fingerprint=?", String.class, fingerprint))
                .isEqualTo("m6-ops");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reversal_suggestion WHERE fingerprint=?", Long.class, fingerprint))
                .isEqualTo(initialSuggestions);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_report WHERE run_id=? AND balanced=0", Long.class, run)).isZero();
    }

    private record CsvFiles(Path marketing, Path accounting, Path channel) {

        private static CsvFiles create() {
            try {
                Path directory = Files.createTempDirectory("recon-m6-csv-");
                directory.toFile().deleteOnExit();
                Path marketing = directory.resolve("marketing.csv");
                Path accounting = directory.resolve("accounting.csv");
                Path channel = directory.resolve("channel.csv");

                String m = "id,order_no,issue_id,ccy,amount_minor,entry_type,biz_status,biz_time,posting_time\n"
                        + "m-clean,\"O,CLEAN\",I-CLEAN,USD,100,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "m-amount,O-AMOUNT,I-AMOUNT,USD,1000,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "m-dup-1,O-DUP,I-DUP,USD,300,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "m-dup-2,O-DUP,I-DUP,USD,300,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "m-group-1,O-GROUP,I-GROUP,USD,400,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "m-group-2,O-GROUP,I-GROUP,USD,-100,REFUND,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "m-currency,O-CURRENCY,I-CURRENCY,USD,200,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "m-status,O-STATUS,I-STATUS,USD,210,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "m-timing,O-TIMING,I-TIMING,USD,220,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "m-bridge1,O-BRIDGE1,I-BRIDGE1,USD,230,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "m-missing,O-MISSING,I-MISSING,USD,240,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "m-reject,O-REJECT,I-REJECT,USD,not-long,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n";

                String a = "id,order_no,issue_id,channel_serial_no,ccy,amount_minor,entry_type,biz_status,biz_time,posting_time\n"
                        + "a-clean,\"O,CLEAN\",I-CLEAN,C-CLEAN,USD,100,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "a-amount,O-AMOUNT,I-AMOUNT,C-AMOUNT,USD,900,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "a-dup,O-DUP,I-DUP,C-DUP,USD,300,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "a-group,O-GROUP,I-GROUP,C-GROUP,USD,500,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "a-currency,O-CURRENCY,I-CURRENCY,C-CURRENCY,EUR,200,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "a-status,O-STATUS,I-STATUS,C-STATUS,USD,210,ISSUE,FAILED,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "a-timing,O-TIMING,I-TIMING,C-TIMING,USD,220,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-18T10:00:00Z\n"
                        + "a-extra,O-EXTRA,I-EXTRA,C-EXTRA,USD,250,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "a-missing,O-MISSING,I-MISSING,C-MISSING,USD,240,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n";

                String c = "id,channel_serial_no,ccy,amount_minor,entry_type,biz_status,biz_time,posting_time\n"
                        + "c-clean,C-CLEAN,USD,100,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "c-amount,C-AMOUNT,USD,900,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "c-dup,C-DUP,USD,300,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "c-group,C-GROUP,USD,500,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "c-currency,C-CURRENCY,EUR,200,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "c-status,C-STATUS,USD,210,ISSUE,FAILED,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "c-timing,C-TIMING,USD,220,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-18T10:00:00Z\n"
                        + "c-extra,C-EXTRA,USD,250,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n"
                        + "c-bridge2,C-BRIDGE2,USD,260,ISSUE,PAID,2026-08-17T10:00:00Z,2026-08-17T10:00:00Z\n";

                writeUtf8Bom(marketing, m);
                Files.writeString(accounting, a, StandardCharsets.UTF_8);
                Files.writeString(channel, c, StandardCharsets.UTF_8);
                List.of(marketing, accounting, channel).forEach(path -> path.toFile().deleteOnExit());
                return new CsvFiles(marketing, accounting, channel);
            } catch (IOException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private static void writeUtf8Bom(Path path, String content) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            output.write(content.getBytes(StandardCharsets.UTF_8));
            Files.write(path, output.toByteArray());
        }
    }
}
