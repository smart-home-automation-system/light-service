package cloud.cholewa.light.occupancy;

import cloud.cholewa.home.model.RoomName;

/** A presence or motion sensor of the device registry, the way the service reads it. */
public record OccupancySensor(long id, String name, RoomName room, SensorKind kind, SensorBinding binding) {
}
