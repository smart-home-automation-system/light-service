package cloud.cholewa.light.rule;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.model.rule.RoomRule;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

//The rules of the rooms as last read (HAS-262), for the engine and the hold of the light reads - a motion
//never waits for the database. Read by the pass of every minute, and changed at once by every write of
//RoomRuleService; a pass that read before a write may bring the old rule back for a minute. Empty until
//the first read: no rule, nothing to do
@Component
public class RuleBook {

    private final AtomicReference<Map<RoomName, RoomRule>> rules = new AtomicReference<>(Map.of());

    public RoomRule rule(final RoomName room) {
        return rules.get().get(room);
    }

    public Map<RoomName, RoomRule> all() {
        return rules.get();
    }

    void replace(final Map<RoomName, RoomRule> read) {
        rules.set(Map.copyOf(read));
    }

    void put(final RoomName room, final RoomRule rule) {
        rules.updateAndGet(held -> {
            final Map<RoomName, RoomRule> changed = new HashMap<>(held);
            changed.put(room, rule);
            return Map.copyOf(changed);
        });
    }

    void remove(final RoomName room) {
        rules.updateAndGet(held -> {
            final Map<RoomName, RoomRule> changed = new HashMap<>(held);
            changed.remove(room);
            return Map.copyOf(changed);
        });
    }
}
