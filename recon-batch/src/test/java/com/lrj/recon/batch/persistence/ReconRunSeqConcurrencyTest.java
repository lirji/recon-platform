package com.lrj.recon.batch.persistence;

import com.lrj.recon.core.application.port.out.ReconRunSeqRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 序号原子分配 (设计 §7/ADR-12): 模拟并发发起同 (scenario, 账期), 断言分配的序号<b>互不重复</b>且恰为
 * {@code 1..N} —— scheduler 与 REST 同一路径无 {@code MAX+1} 竞态。
 */
@SpringBootTest
class ReconRunSeqConcurrencyTest {

    @Autowired ReconRunSeqRepository seqRepo;
    @Autowired JdbcTemplate jdbc;

    private static final String SCENARIO = "SEQ_TEST";
    private static final String PERIOD = "2026-08-17";

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM recon_run_seq");
    }

    @Test
    void concurrentAllocationsAreUniqueAndContiguous() throws Exception {
        int n = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> tasks = IntStream.range(0, n)
                    .<Callable<Integer>>mapToObj(i -> () -> seqRepo.nextSequence(SCENARIO, PERIOD))
                    .toList();
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            List<Integer> seqs = new java.util.ArrayList<>();
            for (Future<Integer> f : futures) {
                seqs.add(f.get());
            }

            // 互不重复 + 恰为 1..N
            assertThat(seqs.stream().collect(Collectors.toSet())).hasSize(n);
            assertThat(seqs).containsExactlyInAnyOrderElementsOf(
                    IntStream.rangeClosed(1, n).boxed().toList());
            // 计数器 next_seq 落在 N+1
            assertThat(jdbc.queryForObject(
                    "SELECT next_seq FROM recon_run_seq WHERE scenario_code=? AND accounting_period=?",
                    Integer.class, SCENARIO, PERIOD)).isEqualTo(n + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sequentialAllocationsStartAtOne() {
        assertThat(seqRepo.nextSequence(SCENARIO, PERIOD)).isEqualTo(1);
        assertThat(seqRepo.nextSequence(SCENARIO, PERIOD)).isEqualTo(2);
        assertThat(seqRepo.nextSequence(SCENARIO, PERIOD)).isEqualTo(3);
        // 不同账期独立计数
        assertThat(seqRepo.nextSequence(SCENARIO, "2026-08-18")).isEqualTo(1);
    }
}
