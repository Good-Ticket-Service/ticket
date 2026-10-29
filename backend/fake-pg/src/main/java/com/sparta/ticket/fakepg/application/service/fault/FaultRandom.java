package com.sparta.ticket.fakepg.application.service.fault;

import java.util.Random;

/**
 * 장애 확률 판단용 난수. seed를 정하면 같은 순서로 들어온 판단에 같은 결과가 나온다.
 *
 * Note: 동시 요청은 도착 순서가 실행마다 달라질 수 있어, seed를 고정해도 거래별 장애가 똑같이 재현된다고 보장하지 않는다.
 * 순차로 보낸 같은 입력의 반복 실험에서만 같은 장애 순서를 기대한다.
 */
public class FaultRandom {

    private Random random;
    private Long seed;

    public FaultRandom(Long seed) {
        reseed(seed);
    }

    /** seed가 null이면 실행마다 다른 난수를 쓴다. 판단 순서도 처음부터 다시 시작한다. */
    public synchronized void reseed(Long seed) {
        this.seed = seed;
        this.random = seed == null ? new Random() : new Random(seed);
    }

    public synchronized double nextDouble() {
        return random.nextDouble();
    }

    public synchronized Long seed() {
        return seed;
    }
}
