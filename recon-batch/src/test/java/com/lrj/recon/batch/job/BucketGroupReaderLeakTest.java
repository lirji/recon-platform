package com.lrj.recon.batch.job;

import com.lrj.recon.core.application.port.out.ReconRecordRepository;
import com.lrj.recon.core.domain.model.ReconRecord;
import com.lrj.recon.core.domain.model.Side;
import com.lrj.recon.core.spi.RecordCursor;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #3 单测: {@link BucketGroupReader#open} 打开 RIGHT 游标抛异常时, 已开的 LEFT 流式游标 (连接) 必须被关闭,
 * 不泄漏。用注入的 fake repository 让 RIGHT cursor 抛错、LEFT cursor 记录 close。
 */
class BucketGroupReaderLeakTest {

    @Test
    void leftCursorClosedWhenRightOpenThrows() {
        AtomicBoolean leftClosed = new AtomicBoolean(false);
        RecordCursor leftCursor = new TrackingCursor(leftClosed);

        ReconRecordRepository repo = new ReconRecordRepository() {
            @Override
            public RecordCursor cursor(String runId, String segmentId, Side side, int bucket) {
                if (side == Side.LEFT) {
                    return leftCursor;
                }
                throw new IllegalStateException("injected RIGHT cursor open failure");
            }

            @Override public void batchInsert(Iterable<ReconRecord> records) { throw new UnsupportedOperationException(); }
            @Override public RecordCursor cursorBySegmentSide(String r, String s, Side side) { throw new UnsupportedOperationException(); }
            @Override public Map<Integer, Long> bucketRowCounts(String r, String s) { throw new UnsupportedOperationException(); }
            @Override public int deleteByRunBounded(String r, int l) { throw new UnsupportedOperationException(); }
        };

        BucketGroupReader reader = new BucketGroupReader(repo, "run", "SEG1_MKT_ACCT", 0, -1, 1);

        assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RIGHT cursor open failure");

        assertThat(leftClosed).as("RIGHT 打开失败时 LEFT 游标必须被关闭 (不泄漏连接)").isTrue();
    }

    /** 记录 close 是否被调用的假游标。 */
    private static final class TrackingCursor implements RecordCursor {
        private final AtomicBoolean closed;

        TrackingCursor(AtomicBoolean closed) {
            this.closed = closed;
        }

        @Override
        public ReconRecord next() {
            return null;
        }

        @Override
        public List<com.lrj.recon.core.spi.RejectedRow> rejects() {
            return List.of();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
