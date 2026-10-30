package com.sparta.ticket.reservation.booking.application.port;

/**
 * 사용자 요청 경로의 payment 호출(결제 준비·승인)이 Tomcat 스레드를 모두 잡지 않게 막는다.
 * payment가 느리거나 멈추면 승인을 기다리는 요청이 스레드를 모두 잡아, 결제와 무관한 좌석 조회까지 줄을 선다.
 * 동시에 기다리는 결제 호출 수를 묶고(느릴 때), payment가 응답하지 않는 일이 이어지면 아예 부르지 않는다(멈췄을 때).
 * 막은 요청은 바로 거절해 스레드를 돌려준다.
 * 스케줄러의 호출에는 쓰지 않는다. 사용자 스레드를 쓰지 않고, 다시 보내야 끝나는 일이기 때문이다.
 */
public interface PaymentCallGuard {

    /** 호출을 시작한다. 막혀 있으면 503 SERVICE_UNAVAILABLE이다. 받은 Call은 셋 중 하나로 반드시 끝낸다. */
    Call start();

    interface Call {

        /** payment가 답했다. 업무 거절도 여기다. */
        void answered();

        /** payment가 응답하지 않았거나(타임아웃, 연결 실패) 서버 오류로 답했다. */
        void unanswered(Throwable cause);

        /** payment를 부르지 않고 끝냈다(이미 결제 중, 상태 오류 등). 판단에 넣지 않는다. */
        void skipped();
    }
}
