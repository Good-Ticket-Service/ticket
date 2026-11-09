package com.sparta.ticket.payment.infrastructure.pgsettlement;

import com.sparta.ticket.payment.application.port.pgsettlement.PgSettlementClient;
import com.sparta.ticket.payment.domain.model.pgsettlement.PgSettlement;
import com.sparta.ticket.payment.infrastructure.pg.PgProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PgSettlementHttpClient implements PgSettlementClient {
    private final Map<String, RestClient> clients;

    public PgSettlementHttpClient(PgProperties properties, RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(65));
        clients = Map.of(
                "TOSS",
                builder.clone()
                        .baseUrl(properties.toss().baseUrl())
                        .requestFactory(factory)
                        .build(),
                "KAKAO",
                builder.clone()
                        .baseUrl(properties.kakao().baseUrl())
                        .requestFactory(factory)
                        .build());
    }

    @Override
    public PgSettlement.Page read(String provider, LocalDate date, String cursor, int size, String snapshot) {
        var client = clients.get(provider);
        if (client == null) throw new IllegalArgumentException("지원하지 않는 PG다");
        return client.get()
                .uri(uri -> uri.path("/fake/settlements")
                        .queryParam("provider", provider)
                        .queryParam("date", date)
                        .queryParam("size", size)
                        .queryParam("cursor", cursor == null ? "" : cursor)
                        .queryParam("snapshotId", snapshot == null ? "" : snapshot)
                        .build())
                .retrieve()
                .body(PgSettlement.Page.class);
    }
}
