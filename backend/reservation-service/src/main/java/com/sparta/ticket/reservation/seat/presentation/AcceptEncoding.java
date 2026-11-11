package com.sparta.ticket.reservation.seat.presentation;

import java.util.Locale;

/**
 * Accept-Encoding 요청 헤더에서 gzip을 받을 수 있는지 판단한다(#287 사전 압축). 서버가 미리 만드는 압축본은 gzip 하나다.
 *
 * 규칙(RFC 9110 12.5.3): gzip(또는 x-gzip)이 q>0으로 있으면 받는다. gzip이 따로 없으면 *의 q를 따른다. q=0은 거절이다.
 * 헤더가 없거나 identity만 있으면 압축하지 않은 원본을 준다.
 */
final class AcceptEncoding {

    private AcceptEncoding() {}

    static boolean acceptsGzip(String header) {
        if (header == null || header.isBlank()) {
            return false;
        }
        Double gzip = null;
        Double any = null;
        for (String part : header.split(",")) {
            String[] fields = part.split(";");
            String coding = fields[0].trim().toLowerCase(Locale.ROOT);
            double q = quality(fields);
            if (coding.equals("gzip") || coding.equals("x-gzip")) {
                gzip = gzip == null ? q : Math.max(gzip, q);
            } else if (coding.equals("*")) {
                any = q;
            }
        }
        if (gzip != null) {
            return gzip > 0;
        }
        return any != null && any > 0;
    }

    /** q 값이 없으면 1이고, 읽을 수 없는 값은 거절(0)로 본다. */
    private static double quality(String[] fields) {
        for (int i = 1; i < fields.length; i++) {
            String param = fields[i].trim();
            if (param.length() > 2 && (param.charAt(0) == 'q' || param.charAt(0) == 'Q') && param.charAt(1) == '=') {
                try {
                    return Double.parseDouble(param.substring(2).trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 1;
    }
}
