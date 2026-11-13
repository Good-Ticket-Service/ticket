package com.sparta.ticket.waitingroom.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.unit.DataSize;

class TomcatSocketBufferConfigTest {

    private final Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");

    @Test
    @DisplayName("설정한 크기를 Tomcat 커넥터의 연결당 읽기·쓰기 버퍼에 넣는다")
    void applySizes() {
        TomcatSocketBufferConfig.apply(
                connector, new TomcatSocketBufferProperties(DataSize.ofKilobytes(2), DataSize.ofKilobytes(4)));

        assertThat(connector.getProperty(TomcatSocketBufferConfig.READ_BUFFER)).hasToString("2048");
        assertThat(connector.getProperty(TomcatSocketBufferConfig.WRITE_BUFFER)).hasToString("4096");
    }

    @Test
    @DisplayName("값이 없으면 Tomcat 기본값(8KB)을 그대로 둔다")
    void keepTomcatDefault() {
        TomcatSocketBufferConfig.apply(connector, new TomcatSocketBufferProperties(null, null));

        assertThat(connector.getProperty(TomcatSocketBufferConfig.READ_BUFFER)).hasToString("8192");
        assertThat(connector.getProperty(TomcatSocketBufferConfig.WRITE_BUFFER)).hasToString("8192");
    }

    @Test
    @DisplayName("0바이트 이하면 기동을 멈춘다")
    void rejectNonPositive() {
        assertThatThrownBy(() -> TomcatSocketBufferConfig.apply(
                        connector, new TomcatSocketBufferProperties(DataSize.ofBytes(0), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Tomcat이 받지 않는 속성 이름이면 조용히 넘어가지 않고 기동을 멈춘다")
    void rejectUnknownProperty() {
        assertThatThrownBy(() ->
                        TomcatSocketBufferConfig.setSize(connector, "socket.noSuchBufSize", DataSize.ofKilobytes(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("설정 파일의 2KB 같은 크기 표기를 읽는다")
    void bindDataSize() {
        new ApplicationContextRunner()
                .withUserConfiguration(TomcatSocketBufferConfig.class)
                .withPropertyValues(
                        "waiting-room.tomcat.socket-buffer.read-size=2KB",
                        "waiting-room.tomcat.socket-buffer.write-size=8KB")
                .run(context -> {
                    TomcatSocketBufferProperties properties = context.getBean(TomcatSocketBufferProperties.class);
                    assertThat(properties.readSize()).isEqualTo(DataSize.ofKilobytes(2));
                    assertThat(properties.writeSize()).isEqualTo(DataSize.ofKilobytes(8));
                });
    }
}
