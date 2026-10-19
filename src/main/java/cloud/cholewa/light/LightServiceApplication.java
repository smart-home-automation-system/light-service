package cloud.cholewa.light;

import cloud.cholewa.light.config.InternalServiceProperties;
import cloud.cholewa.light.config.NotificationConnectionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    NotificationConnectionProperties.class,
    InternalServiceProperties.class
})
public class LightServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LightServiceApplication.class, args);
    }
}
