package com.sparta.ticket.payment.application.service.pg;

import com.sparta.ticket.payment.application.port.pg.PgClient;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 결제에 저장된 PG에 맞는 {@link PgClient}를 고른다. 등록된 구현은 Spring이 모두 주입한다. */
@Component
public class PgClientRegistry {

    private final Map<PgProvider, PgClient> clients = new EnumMap<>(PgProvider.class);

    /** 어댑터가 아직 하나도 없어도 뜨도록 ObjectProvider로 받는다. */
    public PgClientRegistry(ObjectProvider<PgClient> clients) {
        clients.orderedStream().forEach(client -> {
            if (this.clients.putIfAbsent(client.provider(), client) != null) {
                throw new IllegalStateException("PG 구현이 중복 등록됐다: " + client.provider());
            }
        });
    }

    public PgClient get(PgProvider provider) {
        PgClient client = clients.get(provider);
        if (client == null) {
            throw new IllegalStateException("등록된 PG 구현이 없다: " + provider);
        }
        return client;
    }
}
