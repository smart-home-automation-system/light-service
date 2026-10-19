package cloud.cholewa.light.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

//The rules of the rooms run in the memory of this one instance - the deployment is Recreate and
//never scaled, two pods would switch every light twice. Off under the test profile: a
//@SpringBootTest context would otherwise run what the application schedules
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
