package com.lrj.recon.source.csv;

import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.spi.RecordCursor;
import com.lrj.recon.core.spi.RejectedRow;
import com.lrj.recon.core.spi.SourceDescriptor;
import com.lrj.recon.core.spi.SourceReadContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvSourceAdapterTest {

    @TempDir
    Path tempDir;

    private final CsvSourceAdapter adapter = new CsvSourceAdapter();

    @Test
    void streamsUtf8BomQuotedAndMultilineValuesWithPhysicalLineage() throws IOException {
        Path file = tempDir.resolve("marketing.csv");
        String content = header(',')
                + "m-1,\"ORDER,1\",I-1,USD,100,ISSUE,\"PAID\nVERIFIED\",2026-08-18T10:00:00Z,2026-08-18T10:00:00Z\n"
                + "m-2,ORDER-2,I-2,USD,-20,REFUND,PAID,2026-08-18T11:00:00Z,2026-08-18T11:00:00Z\n";
        writeWithBom(file, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, content, StandardCharsets.UTF_8);

        DrainResult result = drain(context(file, Map.of()));

        assertThat(result.records).hasSize(2);
        ReconRecord first = result.records.get(0);
        assertThat(first.groupKey().value()).isEqualTo("ORDER,1");
        assertThat(first.bizStatus()).isEqualTo("PAID\nVERIFIED");
        assertThat(first.rawRef()).endsWith("marketing.csv:2-3");
        assertThat(first.bizTime()).isEqualTo(Instant.parse("2026-08-18T10:00:00Z"));
        assertThat(result.records.get(1).rawRef()).endsWith("marketing.csv:4");
        assertThat(result.records.get(1).signedAmountMinor()).isEqualTo(-20L);
        assertThat(result.rejects).isEmpty();
    }

    @Test
    void detectsUtf16LeBomAndSupportsCustomDelimiter() throws IOException {
        Path file = tempDir.resolve("accounting.csv");
        String content = header(';')
                + "a-1;ORDER-1;I-1;TWD;123;ISSUE;PAID;2026-08-18T10:00:00Z;2026-08-18T10:00:00Z\n";
        writeWithBom(file, new byte[]{(byte) 0xFF, (byte) 0xFE}, content, StandardCharsets.UTF_16LE);

        DrainResult result = drain(context(file, Map.of("delimiter", ";")));

        assertThat(result.records).singleElement().satisfies(record -> {
            assertThat(record.currency()).isEqualTo("TWD");
            assertThat(record.matchKey().value()).isEqualTo("I-1");
            assertThat(record.rawRef()).endsWith("accounting.csv:2");
        });
    }

    @Test
    void rejectsSemanticRowsAndContinuesWithLaterRecords() throws IOException {
        Path file = tempDir.resolve("rejects.csv");
        Files.writeString(file, header(',')
                + "ok-1,ORDER-1,I-1,USD,100,ISSUE,PAID,2026-08-18T10:00:00Z,2026-08-18T10:00:00Z\n"
                + "bad-amount,ORDER-2,I-2,USD,not-long,ISSUE,PAID,2026-08-18T10:00:00Z,2026-08-18T10:00:00Z\n"
                + "bad-group,,I-3,USD,300,ISSUE,PAID,2026-08-18T10:00:00Z,2026-08-18T10:00:00Z\n"
                + "bad-entry,ORDER-4,I-4,USD,400,UNKNOWN,PAID,2026-08-18T10:00:00Z,2026-08-18T10:00:00Z\n"
                + "ok-5,ORDER-5,I-5,USD,500,ISSUE,PAID,2026-08-18T10:00:00Z,2026-08-18T10:00:00Z\n");

        DrainResult result = drain(context(file, Map.of()));

        assertThat(result.records).extracting(r -> r.matchKey().value()).containsExactly("I-1", "I-5");
        assertThat(result.rejects).extracting(RejectedRow::rawRef)
                .allSatisfy(ref -> assertThat(ref).contains("rejects.csv:"));
        assertThat(result.rejects).extracting(RejectedRow::reason)
                .anyMatch(reason -> reason.contains("not a signed long"))
                .anyMatch(reason -> reason.contains("blank required column 'order_no'"))
                .anyMatch(reason -> reason.contains("unknown entry_type"));
    }

    @Test
    void recordsUnrecoverableSyntaxFailureAfterReturningEarlierRows() throws IOException {
        Path file = tempDir.resolve("syntax.csv");
        Files.writeString(file, header(',')
                + "ok-1,ORDER-1,I-1,USD,100,ISSUE,PAID,2026-08-18T10:00:00Z,2026-08-18T10:00:00Z\n"
                + "bad,\"unterminated,I-2,USD,200,ISSUE,PAID,2026-08-18T10:00:00Z,2026-08-18T10:00:00Z");

        DrainResult result = drain(context(file, Map.of()));

        assertThat(result.records).singleElement().satisfies(record ->
                assertThat(record.matchKey().value()).isEqualTo("I-1"));
        assertThat(result.rejects).singleElement().satisfies(reject -> {
            assertThat(reject.rawRef()).contains("syntax.csv:3");
            assertThat(reject.reason()).contains("invalid CSV syntax or encoding");
        });
    }

    @Test
    void failsFastForMissingHeaderAndCharsetConflict() throws IOException {
        Path missingHeader = tempDir.resolve("missing.csv");
        Files.writeString(missingHeader, "id,order_no,issue_id,ccy\n1,O-1,I-1,USD\n");
        assertThatThrownBy(() -> adapter.open(context(missingHeader, Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required headers")
                .hasMessageContaining("amount_minor");

        Path conflict = tempDir.resolve("conflict.csv");
        writeWithBom(conflict, new byte[]{(byte) 0xFF, (byte) 0xFE}, header(';'), StandardCharsets.UTF_16LE);
        assertThatThrownBy(() -> adapter.open(context(conflict, Map.of("charset", "UTF-8", "delimiter", ";"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicts with configured charset");
    }

    @Test
    void validatesDescriptorAndCursorLifecycle() throws IOException {
        assertThat(adapter.sourceId()).isEqualTo("csv-file");
        assertThat(adapter.supports(new SourceDescriptor("db", Map.of()))).isFalse();

        Path file = tempDir.resolve("one.csv");
        Files.writeString(file, header(',')
                + "1,O-1,I-1,USD,1,ISSUE,PAID,2026-08-18T10:00:00Z,2026-08-18T10:00:00Z\n");
        RecordCursor cursor = adapter.open(context(file, Map.of()));
        cursor.close();
        assertThatThrownBy(cursor::next).isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }

    private SourceReadContext context(Path path, Map<String, String> overrides) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("path", path.toString());
        params.put("idColumn", "id");
        params.put("matchKeyColumn", "issue_id");
        params.put("matchKeyField", "marketingIssueId");
        params.put("groupKeyColumn", "order_no");
        params.put("groupKeyField", "orderNo");
        params.put("currencyColumn", "ccy");
        params.put("amountColumn", "amount_minor");
        params.put("entryTypeColumn", "entry_type");
        params.put("bizStatusColumn", "biz_status");
        params.put("bizTimeColumn", "biz_time");
        params.put("postingTimeColumn", "posting_time");
        params.putAll(overrides);
        return new SourceReadContext("run-csv", "SEG1_MKT_ACCT", Side.LEFT, SourceRole.MARKETING, 64,
                new SourceDescriptor(CsvSourceAdapter.SOURCE_TYPE, params));
    }

    private DrainResult drain(SourceReadContext context) {
        List<ReconRecord> records = new ArrayList<>();
        List<RejectedRow> rejects;
        try (RecordCursor cursor = adapter.open(context)) {
            ReconRecord record;
            while ((record = cursor.next()) != null) {
                records.add(record);
            }
            rejects = cursor.rejects();
        }
        return new DrainResult(records, rejects);
    }

    private static String header(char delimiter) {
        return String.join(String.valueOf(delimiter), "id", "order_no", "issue_id", "ccy", "amount_minor",
                "entry_type", "biz_status", "biz_time", "posting_time") + "\n";
    }

    private static void writeWithBom(Path path, byte[] bom, String content, Charset charset) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(bom);
        bytes.write(content.getBytes(charset));
        Files.write(path, bytes.toByteArray());
    }

    private record DrainResult(List<ReconRecord> records, List<RejectedRow> rejects) {
    }
}
