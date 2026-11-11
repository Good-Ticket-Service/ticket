package com.sparta.ticket.payment.infrastructure.messaging;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentConfirmOwnership implements ConsumerAwareRebalanceListener {
    public record Lease(UUID id, AtomicBoolean active) {
        public synchronized void invalidate() {
            active.set(false);
        }

        public synchronized boolean acknowledgeIfOwned(Runnable action) {
            if (!active.get()) return false;
            action.run();
            return true;
        }
    }

    private final Map<TopicPartition, Lease> owned = new ConcurrentHashMap<>();

    @Override
    public void onPartitionsAssigned(
            org.apache.kafka.clients.consumer.Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        partitions.forEach(partition ->
                owned.computeIfAbsent(partition, ignored -> new Lease(UUID.randomUUID(), new AtomicBoolean(true))));
    }

    @Override
    public void onPartitionsRevokedBeforeCommit(
            org.apache.kafka.clients.consumer.Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        revoke(partitions);
    }

    @Override
    public void onPartitionsLost(
            org.apache.kafka.clients.consumer.Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        revoke(partitions);
    }

    private void revoke(Collection<TopicPartition> partitions) {
        partitions.forEach(partition -> {
            Lease lease = owned.remove(partition);
            if (lease != null) lease.invalidate();
        });
    }

    public Lease lease(TopicPartition partition) {
        return owned.get(partition);
    }

    public void close() {
        revoke(java.util.List.copyOf(owned.keySet()));
    }
}
