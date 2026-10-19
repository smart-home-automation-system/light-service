package cloud.cholewa.light.occupancy;

import cloud.cholewa.home.model.EatonGatewayType;

/** How a sensor of the occupancy reports, and so which channel of the service hears it. */
public sealed interface SensorBinding {

    /** A Shelly Presence Gen4: one zone of it (presencezone:&lt;zone&gt;), over MQTT under its id in lower case. */
    record Presence(String address, String deviceId, int zone) implements SensorBinding {
    }

    /** A Shelly BLU Motion, by its Bluetooth address in lower case, delivered by any BTHome gateway of the registry. */
    record BluMotion(String address) implements SensorBinding {
    }

    /** A Shelly Motion of the first generation: it sleeps, and reports by its actions alone. */
    record Gen1Motion(String address) implements SensorBinding {
    }

    /** A PIR sensor of the AMX system, through amx-service (HAS-288). */
    record EatonMotion(EatonGatewayType gateway, int point) implements SensorBinding {
    }
}
