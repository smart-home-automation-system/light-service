package cloud.cholewa.light.error;

import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.light.model.Light;
import lombok.Getter;
import org.springframework.http.HttpStatus;

//A command that was not carried out, or may not have been, or a rule that was not written. The status says whose problem it is,
//the error names the cause. The message is for the log; it is answered as details only for a 4xx -
//for a failed device it quotes the device, and that never goes into an answer
@Getter
public class LightException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final LightError error;

    public LightException(final HttpStatus httpStatus, final LightError error, final String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.error = error;
    }

    public static LightException notFound(final String what) {
        return new LightException(HttpStatus.NOT_FOUND, LightError.NOT_FOUND_LIGHT, what);
    }

    public static LightException unsupported(final Light light, final String what) {
        return new LightException(
            HttpStatus.BAD_REQUEST,
            LightError.UNSUPPORTED_CAPABILITY,
            "light " + light.id() + " (" + light.kind().getValue() + ") cannot take " + what
        );
    }

    public static LightException registryUnavailable(final Throwable cause) {
        return new LightException(
            HttpStatus.BAD_GATEWAY,
            LightError.REGISTRY_UNAVAILABLE,
            "the device registry was never read: " + describe(cause)
        );
    }

    public static LightException invalidRule(final String reason) {
        return new LightException(HttpStatus.BAD_REQUEST, LightError.INVALID_RULE, reason);
    }

    public static LightException ruleNotFound(final RoomName room) {
        return new LightException(HttpStatus.NOT_FOUND, LightError.NOT_FOUND_RULE, "no rule for the room " + room.getValue());
    }

    //the sensors of a room are needed to judge a rule, and they are the registry's too
    public static LightException sensorsUnavailable() {
        return new LightException(HttpStatus.BAD_GATEWAY, LightError.REGISTRY_UNAVAILABLE,
            "the sensors of the device registry were never read");
    }

    public static LightException deviceFailed(final Light light, final Throwable cause) {
        return new LightException(
            HttpStatus.BAD_GATEWAY,
            LightError.DEVICE_FAILED,
            "light " + light.id() + " (" + light.name() + "): " + describe(cause)
        );
    }

    //the type alone when an exception has no message - "null" names nothing
    private static String describe(final Throwable cause) {
        return cause.getMessage() == null
            ? cause.getClass().getSimpleName()
            : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
