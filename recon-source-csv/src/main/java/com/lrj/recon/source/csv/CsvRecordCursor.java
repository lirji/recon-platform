package com.lrj.recon.source.csv;

import com.lrj.recon.core.domain.model.EntryType;
import com.lrj.recon.core.domain.model.GroupKey;
import com.lrj.recon.core.domain.model.MatchKey;
import com.lrj.recon.core.domain.model.Money;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.service.Bucketing;
import com.lrj.recon.core.spi.RecordCursor;
import com.lrj.recon.core.spi.RejectedRow;
import com.lrj.recon.core.spi.SourceReadContext;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Commons CSV 流式游标；内存仅保留当前记录与 reject 元数据，不 materialize 整个文件。 */
final class CsvRecordCursor implements RecordCursor {

    private final SourceReadContext context;
    private final CsvSourceConfig config;
    private final CSVParser parser;
    private final Iterator<CSVRecord> iterator;
    private final List<RejectedRow> rejects = new ArrayList<>();

    private long nextStartLine;
    private boolean exhausted;
    private boolean closed;

    private CsvRecordCursor(SourceReadContext context, CsvSourceConfig config, CSVParser parser) {
        this.context = context;
        this.config = config;
        this.parser = parser;
        this.iterator = parser.iterator();
        this.nextStartLine = parser.getCurrentLineNumber() + 1;
        validateHeaders(parser.getHeaderNames(), config.requiredHeaders(), config.path.toString());
    }

    static CsvRecordCursor open(SourceReadContext context, CsvSourceConfig config) {
        Reader reader = null;
        try {
            reader = BomAwareReader.open(config);
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setDelimiter(config.delimiter)
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setAllowDuplicateHeaderNames(false)
                    .setAllowMissingColumnNames(false)
                    .setIgnoreEmptyLines(false)
                    .setLenientEof(false)
                    .setTrailingData(false)
                    .get();
            CSVParser parser = CSVParser.parse(reader, format);
            return new CsvRecordCursor(context, config, parser);
        } catch (IOException failure) {
            closeQuietly(reader);
            throw new IllegalArgumentException("cannot open CSV source " + config.path + ": "
                    + failure.getMessage(), failure);
        } catch (RuntimeException failure) {
            closeQuietly(reader);
            throw failure;
        }
    }

    @Override
    public ReconRecord next() {
        requireOpen();
        while (!exhausted) {
            CSVRecord csv;
            long startLine = nextStartLine;
            try {
                if (!iterator.hasNext()) {
                    exhausted = true;
                    return null;
                }
                csv = iterator.next();
            } catch (UncheckedIOException syntaxFailure) {
                reject(rawRef(startLine, parser.getCurrentLineNumber()),
                        "invalid CSV syntax or encoding: " + syntaxFailure.getCause().getMessage(), null);
                exhausted = true;
                return null;
            }

            long endLine = Math.max(startLine, parser.getCurrentLineNumber());
            nextStartLine = endLine + 1;
            String rawRef = rawRef(startLine, endLine);
            try {
                return standardize(csv, rawRef);
            } catch (RuntimeException invalidRow) {
                reject(rawRef, "invalid row: " + message(invalidRow), csv.toString());
            }
        }
        return null;
    }

    private ReconRecord standardize(CSVRecord csv, String rawRef) {
        if (!csv.isConsistent()) {
            throw new IllegalArgumentException("column count does not match header");
        }
        required(csv, config.idColumn);
        String groupValue = required(csv, config.groupKeyColumn);
        String currency = required(csv, config.currencyColumn).trim().toUpperCase(Locale.ROOT);
        long amount = parseLong(required(csv, config.amountColumn), config.amountColumn);
        Money money = Money.of(currency, amount);
        int bucket = Bucketing.bucketOf(groupValue, context.bucketCount());

        String matchValue = optional(csv, config.matchKeyColumn);
        MatchKey matchKey = matchValue == null ? null
                : MatchKey.of(config.matchKeyField, matchValue, bucket);

        return ReconRecord.builder()
                .recordId(context.runId() + ":" + context.segmentId() + ":" + context.side().name()
                        + ":" + rawRef)
                .runId(context.runId())
                .segmentId(context.segmentId())
                .side(context.side())
                .sourceRole(context.sourceRole())
                .matchKey(matchKey)
                .groupKey(GroupKey.of(config.groupKeyField, groupValue))
                .bucket(bucket)
                .money(money)
                .entryType(entryType(optional(csv, config.entryTypeColumn)))
                .bizStatus(optional(csv, config.bizStatusColumn))
                .bizTime(instant(config.bizTimeColumn == null ? null : required(csv, config.bizTimeColumn),
                        config.bizTimeColumn))
                .postingTime(instant(optional(csv, config.postingTimeColumn), config.postingTimeColumn))
                .rawRef(rawRef)
                .build();
    }

    private static String required(CSVRecord csv, String column) {
        String value = optional(csv, column);
        if (value == null) {
            throw new IllegalArgumentException("blank required column '" + column + "'");
        }
        return value;
    }

    private static String optional(CSVRecord csv, String column) {
        if (column == null) {
            return null;
        }
        if (!csv.isMapped(column) || !csv.isSet(column)) {
            throw new IllegalArgumentException("missing mapped column '" + column + "'");
        }
        String value = csv.get(column);
        return value == null || value.isBlank() ? null : value;
    }

    private static long parseLong(String value, String column) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("column '" + column + "' is not a signed long: " + value, invalid);
        }
    }

    private static EntryType entryType(String value) {
        if (value == null) {
            return EntryType.ISSUE;
        }
        try {
            return EntryType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("unknown entry_type: " + value, invalid);
        }
    }

    private static Instant instant(String value, String column) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("column '" + column + "' is not an ISO-8601 instant: " + value,
                    invalid);
        }
    }

    private String rawRef(long startLine, long endLine) {
        return config.path + ":" + (endLine > startLine ? startLine + "-" + endLine : Long.toString(startLine));
    }

    private void reject(String rawRef, String reason, String payload) {
        rejects.add(new RejectedRow(rawRef, reason, payload));
    }

    private static String message(RuntimeException failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static void validateHeaders(List<String> actual, Set<String> required, String path) {
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("CSV " + path + " missing required headers: " + missing);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("CSV cursor is closed: " + config.path);
        }
    }

    @Override
    public List<RejectedRow> rejects() {
        return List.copyOf(rejects);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        exhausted = true;
        try {
            parser.close();
        } catch (IOException failure) {
            throw new IllegalStateException("failed closing CSV source " + config.path, failure);
        }
    }

    private static void closeQuietly(Reader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
                // Preserve the original open/parse failure.
            }
        }
    }
}
