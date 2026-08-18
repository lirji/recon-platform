package com.lrj.recon.batch.config;

import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.domain.model.SourceRole;
import com.lrj.recon.core.spi.RecordCursor;
import com.lrj.recon.core.spi.SourceAdapter;
import com.lrj.recon.core.spi.SourceDescriptor;
import com.lrj.recon.core.spi.SourceReadContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingSourceAdapterTest {

    @Test
    void routesBySourceTypeAndRejectsUnknownType() {
        StubAdapter db = new StubAdapter("db");
        StubAdapter csv = new StubAdapter("csv-file");
        RoutingSourceAdapter router = new RoutingSourceAdapter(List.of(db, csv));

        SourceReadContext csvContext = context("csv-file");
        assertThat(router.supports(csvContext.descriptor())).isTrue();
        assertThat(router.open(csvContext)).isSameAs(csv.cursor);
        assertThat(csv.opens).isEqualTo(1);
        assertThat(db.opens).isZero();

        SourceReadContext unknown = context("api");
        assertThat(router.supports(unknown.descriptor())).isFalse();
        assertThatThrownBy(() -> router.open(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("available")
                .hasMessageContaining("csv-file");
    }

    @Test
    void rejectsDuplicateSourceIds() {
        assertThatThrownBy(() -> new RoutingSourceAdapter(List.of(
                new StubAdapter("db"), new StubAdapter("db"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate SourceAdapter sourceId: db");
    }

    private static SourceReadContext context(String type) {
        return new SourceReadContext("run", "segment", Side.LEFT, SourceRole.MARKETING, 8,
                new SourceDescriptor(type, Map.of()));
    }

    private static final class StubAdapter implements SourceAdapter {
        private final String id;
        private final RecordCursor cursor = new EmptyCursor();
        private int opens;

        private StubAdapter(String id) {
            this.id = id;
        }

        @Override
        public String sourceId() {
            return id;
        }

        @Override
        public boolean supports(SourceDescriptor descriptor) {
            return descriptor != null && id.equals(descriptor.sourceType());
        }

        @Override
        public RecordCursor open(SourceReadContext context) {
            opens++;
            return cursor;
        }
    }

    private static final class EmptyCursor implements RecordCursor {
        @Override
        public com.lrj.recon.core.domain.model.ReconRecord next() {
            return null;
        }

        @Override
        public void close() {
        }
    }
}
