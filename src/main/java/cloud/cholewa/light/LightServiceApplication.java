package cloud.cholewa.light;

import cloud.cholewa.light.config.EmptyHouseProperties;
import cloud.cholewa.light.config.InternalServiceProperties;
import cloud.cholewa.light.config.LocationProperties;
import cloud.cholewa.light.config.MonitorProperties;
import cloud.cholewa.light.config.NotificationConnectionProperties;
import cloud.cholewa.light.config.OccupancyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    NotificationConnectionProperties.class,
    InternalServiceProperties.class,
    OccupancyProperties.class,
    EmptyHouseProperties.class,
    LocationProperties.class,
    MonitorProperties.class
})
public class LightServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LightServiceApplication.class, args);
    }
}
