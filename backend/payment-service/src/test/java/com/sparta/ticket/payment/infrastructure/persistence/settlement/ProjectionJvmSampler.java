package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.management.ObjectName;

/** 시험 JVM의 CPU·heap·GC와 Kafka task/state/restore 지표를 파일에 보존한다. */
final class ProjectionJvmSampler implements AutoCloseable {
    private final java.util.concurrent.ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final ObjectMapper json = new ObjectMapper();
    private final io.micrometer.core.instrument.MeterRegistry meters;

    ProjectionJvmSampler(Path output) throws Exception {
        this(output, null);
    }

    ProjectionJvmSampler(Path output, io.micrometer.core.instrument.MeterRegistry meters) throws Exception {
        this.meters = meters;
        Files.createDirectories(output.toAbsolutePath().getParent());
        executor.scheduleAtFixedRate(
                () -> {
                    try {
                        Files.writeString(
                                output,
                                json.writeValueAsString(sample()) + "\n",
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                    } catch (Exception exception) {
                        System.err.println("measurement-sampler-failed:"
                                + exception.getClass().getSimpleName());
                    }
                },
                0,
                1,
                TimeUnit.SECONDS);
    }

    private Map<String, Object> sample() throws Exception {
        var values = new LinkedHashMap<String, Object>();
        values.put("epochMs", System.currentTimeMillis());
        values.put("pid", ProcessHandle.current().pid());
        values.put(
                "cpuNanos",
                ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                        .getProcessCpuTime());
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        values.put("heapUsed", heap.getUsed());
        values.put("heapCommitted", heap.getCommitted());
        values.put("heapMax", heap.getMax());
        values.put(
                "nonHeapUsed",
                ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
        values.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        var gc = new LinkedHashMap<String, Object>();
        ManagementFactory.getGarbageCollectorMXBeans()
                .forEach(bean -> gc.put(
                        bean.getName(),
                        Map.of("count", bean.getCollectionCount(), "timeMs", bean.getCollectionTime())));
        values.put("gc", gc);
        var buffers = new LinkedHashMap<String, Object>();
        ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)
                .forEach(bean -> buffers.put(
                        bean.getName(),
                        Map.of(
                                "used",
                                bean.getMemoryUsed(),
                                "capacity",
                                bean.getTotalCapacity(),
                                "count",
                                bean.getCount())));
        values.put("buffers", buffers);
        var server = ManagementFactory.getPlatformMBeanServer();
        var kafka = new LinkedHashMap<String, Object>();
        for (var name : server.queryNames(new ObjectName("kafka.*:*"), null)) {
            var metrics = new LinkedHashMap<String, Object>();
            for (var attribute : server.getMBeanInfo(name).getAttributes()) {
                if (!attribute.isReadable()) continue;
                try {
                    Object value = server.getAttribute(name, attribute.getName());
                    if (value instanceof Number) metrics.put(attribute.getName(), value);
                } catch (javax.management.JMException ignored) {
                    metrics.put(attribute.getName(), "unavailable");
                }
            }
            kafka.put(name.toString(), metrics);
        }
        values.put("kafka", kafka);
        if (meters != null) {
            var projection = new LinkedHashMap<String, Object>();
            for (var meter : meters.getMeters()) {
                var measurements = new LinkedHashMap<String, Object>();
                for (var measurement : meter.measure()) {
                    double value = measurement.getValue();
                    measurements.put(measurement.getStatistic().name(), Double.isFinite(value) ? value : "unknown");
                }
                projection.put(meter.getId().getName() + meter.getId().getTags(), measurements);
            }
            values.put("projectionMeters", projection);
        }
        return values;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
