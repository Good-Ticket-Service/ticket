package com.sparta.ticket.fakepg.application.service.settlement;

import com.sparta.ticket.fakepg.application.service.fault.FaultInjector;
import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import com.sparta.ticket.fakepg.domain.model.pg.PgOperation;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** 거래 시각으로 확정 과거 명세를 계산한다. 명세·입금 테이블이나 지급 실행기는 만들지 않는다. */
@Service
@EnableConfigurationProperties(FakeSettlementProperties.class)
public class FakeSettlementService {
    public record Line(
            String lineKey,
            String paymentKey,
            String orderId,
            String type,
            long amount,
            long fee,
            LocalDateTime occurredAt) {}

    public record Totals(long count, long amount, long fee, long carryIn, long payout, long carryOut) {}

    public record Page(
            String provider,
            LocalDate settlementDate,
            String snapshotId,
            Totals totals,
            List<Line> lines,
            String nextCursor) {}

    public record Fault(String paymentKey, boolean hidden, long amountDelta, long feeDelta, int dateShiftDays) {
        public Fault {
            if (Math.abs(amountDelta) > 1000000 || Math.abs(feeDelta) > 1000000 || Math.abs(dateShiftDays) > 10)
                throw new IllegalArgumentException("명세 장애 주입 범위를 넘었다");
            paymentKey = paymentKey == null ? "" : paymentKey;
        }

        public static Fault none() {
            return new Fault("", false, 0, 0, 0);
        }
    }

    private record Header(String id, Totals totals) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final FakeSettlementProperties properties;
    private final Clock clock;
    private final FaultInjector faults;
    private final Map<PgProvider, Fault> overrides = new ConcurrentHashMap<>();
    private final Map<String, Header> headers = new ConcurrentHashMap<>();

    public FakeSettlementService(
            NamedParameterJdbcTemplate jdbc, FakeSettlementProperties properties, Clock clock, FaultInjector faults) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.faults = faults;
    }

    public void fault(PgProvider provider, Fault fault) {
        overrides.put(provider, fault);
    }

    public void resetFaults() {
        overrides.clear();
        headers.clear();
    }

    public Page read(PgProvider provider, LocalDate date, String cursor, int size, String snapshot) {
        if (size < 1
                || size > 5000
                || !date.isBefore(LocalDate.now(clock))
                || date.isBefore(properties.startDate())
                || date.isAfter(properties.coveredUntil())) throw new PgException(PgErrorCode.INVALID_REQUEST);
        faults.checkRateLimit(provider, PgOperation.SETTLEMENT);
        faults.beforeProcessing(provider, PgOperation.SETTLEMENT);
        Fault fault = overrides.getOrDefault(provider, Fault.none());
        var params = parameters(provider, date, fault);
        String cacheKey = provider + ":" + date + ":" + fault;
        Header header = cursor.isEmpty() || !headers.containsKey(cacheKey)
                ? summarize(params, provider, date, fault)
                : headers.get(cacheKey);
        if (snapshot != null && !snapshot.isEmpty() && !snapshot.equals(header.id()))
            throw new PgException(PgErrorCode.INVALID_REQUEST, "명세가 변경됐다");
        if (headers.size() > 256) headers.clear();
        headers.put(cacheKey, header);
        params.addValue("from", date.minusDays(110).atStartOfDay())
                .addValue("cursor", cursor)
                .addValue("size", size + 1);
        List<Line> lines = jdbc.query(
                EVENTS + """
                 SELECT * FROM events WHERE settlement_date=:date AND line_key COLLATE "C">CAST(:cursor AS text) COLLATE "C"
                   ORDER BY line_key COLLATE "C" LIMIT :size
                """,
                params,
                (rs, n) -> new Line(
                        rs.getString("line_key"),
                        rs.getString("payment_key"),
                        rs.getString("order_id"),
                        rs.getString("type"),
                        rs.getLong("amount"),
                        rs.getLong("fee"),
                        rs.getObject("occurred_at", LocalDateTime.class)));
        String next = lines.size() > size ? lines.get(size - 1).lineKey() : null;
        if (lines.size() > size) lines = new ArrayList<>(lines.subList(0, size));
        if (next == null) {
            params.addValue("from", properties.startDate().atStartOfDay());
            params.addValue("cursor", "");
            Header last = summarize(params, provider, date, fault);
            if (!last.id().equals(header.id())) throw new PgException(PgErrorCode.INVALID_REQUEST, "명세 수집 중 원본이 변경됐다");
        }
        return new Page(provider.name(), date, header.id(), header.totals(), lines, next);
    }

    private Header summarize(MapSqlParameterSource args, PgProvider provider, LocalDate date, Fault fault) {
        var row = jdbc.queryForMap(EVENTS + """
                , daily AS (SELECT settlement_date,sum(amount-fee) AS net FROM events
                    WHERE settlement_date<:date GROUP BY settlement_date),
                running AS (SELECT net,sum(net) OVER(ORDER BY settlement_date) AS balance FROM daily),
                carry AS (SELECT greatest(0,coalesce(max(balance),0))-coalesce(sum(net),0) AS amount FROM running)
                SELECT count(*) FILTER(WHERE settlement_date=:date) AS count,
                  coalesce(sum(amount) FILTER(WHERE settlement_date=:date),0) AS amount,
                  coalesce(sum(fee) FILTER(WHERE settlement_date=:date),0) AS fee,
                  coalesce(sum(hashtextextended(line_key||':'||amount||':'||fee||':'||occurred_at,0)::numeric)
                    FILTER(WHERE settlement_date=:date),0)::text AS checksum,
                  (SELECT amount FROM carry) AS carry_in FROM events
                """, args);
        long amount = ((Number) row.get("amount")).longValue();
        long fee = ((Number) row.get("fee")).longValue();
        long carry = ((Number) row.get("carry_in")).longValue();
        long net = Math.subtractExact(Math.subtractExact(amount, fee), carry);
        var totals = new Totals(
                ((Number) row.get("count")).longValue(), amount, fee, carry, Math.max(net, 0), Math.max(-net, 0));
        String policy = properties.version() + ":" + properties.startDate() + ":" + properties.coveredUntil() + ":"
                + properties.providers().get(provider.name()) + ":"
                + properties.holidays().stream().sorted().toList();
        return new Header(
                digest(provider + ":" + date + ":" + policy + ":" + fault + ":" + totals + ":" + row.get("checksum")),
                totals);
    }

    private MapSqlParameterSource parameters(PgProvider provider, LocalDate date, Fault fault) {
        var fee = properties.providers().get(provider.name());
        List<String> sources = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (LocalDate source = properties.startDate(); !source.isAfter(date); source = source.plusDays(1)) {
            sources.add(source.toString());
            targets.add(settlementDate(source, fee.businessDays()).toString());
        }
        return new MapSqlParameterSource()
                .addValue("sources", "{" + String.join(",", sources) + "}")
                .addValue("targets", "{" + String.join(",", targets) + "}")
                .addValue("provider", provider.name())
                .addValue("date", date)
                .addValue("rate", fee.rateBp())
                .addValue("vat", fee.vatRateBp())
                .addValue("cursor", "")
                .addValue("until", date.plusDays(1).atStartOfDay())
                .addValue("from", properties.startDate().atStartOfDay())
                .addValue("faultKey", fault.paymentKey())
                .addValue("hidden", fault.hidden())
                .addValue("amountDelta", fault.amountDelta())
                .addValue("feeDelta", fault.feeDelta())
                .addValue("dateShift", fault.dateShiftDays());
    }

    LocalDate settlementDate(LocalDate source, int days) {
        LocalDate day = source;
        int traversed = 0;
        while (days > 0) {
            day = day.plusDays(1);
            if (day.isAfter(properties.coveredUntil()) || ++traversed > 100)
                throw new IllegalArgumentException("시험용 정산 달력 범위를 넘었다");
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY
                    && day.getDayOfWeek() != DayOfWeek.SUNDAY
                    && !properties.holidays().contains(day)) days--;
        }
        return day;
    }

    private static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String EVENTS = """
            WITH calendar AS (SELECT * FROM unnest(CAST(:sources AS date[]),CAST(:targets AS date[])) c(source,target)),
            approvals AS NOT MATERIALIZED (
              SELECT p.*,c.target AS approval_date,
                (p.amount::bigint*:rate/10000)+(p.amount::bigint*:rate/10000)*:vat/10000 AS original_fee
              FROM pg_transaction p JOIN calendar c ON c.source=p.approved_at::date
              WHERE p.provider=:provider AND p.approved_at<:until
                AND (p.approved_at>=:from OR p.canceled_at>=:from)
                AND ((p.payment_key||':APPROVAL') COLLATE "C">CAST(:cursor AS text) COLLATE "C"
                  OR (p.payment_key||':CANCEL') COLLATE "C">CAST(:cursor AS text) COLLATE "C")),
            raw AS (
              SELECT payment_key||':APPROVAL' AS line_key,payment_key,order_id,'APPROVAL' AS type,
                amount::bigint,original_fee AS fee,approved_at AS occurred_at,approval_date AS settlement_date FROM approvals
              UNION ALL
              SELECT p.payment_key||':CANCEL',p.payment_key,p.order_id,'CANCEL',
                -coalesce(p.canceled_amount,p.amount)::bigint,-p.original_fee,p.canceled_at,
                CASE WHEN p.canceled_at<p.approval_date::timestamp THEN p.approval_date ELSE c.target END
              FROM approvals p LEFT JOIN calendar c ON c.source=p.canceled_at::date
              WHERE p.canceled_at IS NOT NULL AND p.canceled_at<:until),
            events AS (
              SELECT line_key,payment_key,order_id,type,
                amount+CASE WHEN payment_key=:faultKey THEN sign(amount)::bigint*:amountDelta ELSE 0 END AS amount,
                fee+CASE WHEN payment_key=:faultKey THEN sign(amount)::bigint*:feeDelta ELSE 0 END AS fee,occurred_at,
                settlement_date+CASE WHEN payment_key=:faultKey THEN :dateShift ELSE 0 END AS settlement_date
              FROM raw WHERE NOT (:hidden AND payment_key=:faultKey))
            """;
}
