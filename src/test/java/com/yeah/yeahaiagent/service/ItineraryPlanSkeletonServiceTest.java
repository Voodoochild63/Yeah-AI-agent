package com.yeah.yeahaiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeah.yeahaiagent.config.CacheConfig;
import com.yeah.yeahaiagent.model.dto.ItineraryNode;
import com.yeah.yeahaiagent.model.dto.ItineraryPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("规划骨架 Caffeine 单飞加载")
class ItineraryPlanSkeletonServiceTest {

    @Test
    @DisplayName("并发相同 key 只计算一次，返回各自拷贝")
    void concurrentSameKeyComputesOnce() throws Exception {
        ItineraryPlanSkeletonService service = new ItineraryPlanSkeletonService(
                new CacheConfig().cacheManager(), new ObjectMapper());
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<ItineraryPlan>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.getOrCompute("bondi|coastal|half-day|zh|-|bondi", () -> {
                        loads.incrementAndGet();
                        sleepQuietly();
                        return samplePlan();
                    });
                }));
            }
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ItineraryPlan> plans = new ArrayList<>();
            for (Future<ItineraryPlan> future : futures) {
                plans.add(future.get(3, TimeUnit.SECONDS));
            }
            assertThat(loads.get()).isEqualTo(1);
            assertThat(plans).hasSize(8);
            assertThat(plans).allSatisfy(plan -> assertThat(plan.getTitle()).isEqualTo("Bondi half-day"));
            plans.get(0).setTitle("mutated");
            assertThat(plans.get(1).getTitle()).isEqualTo("Bondi half-day");
        } finally {
            pool.shutdownNow();
        }
    }

    private static ItineraryPlan samplePlan() {
        ItineraryNode node = new ItineraryNode();
        node.setPoiName("Bondi Icebergs");
        ItineraryPlan plan = new ItineraryPlan();
        plan.setTitle("Bondi half-day");
        plan.setSchedule(new ArrayList<>(List.of(node)));
        return plan;
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(80);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
