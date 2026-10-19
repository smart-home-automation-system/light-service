package cloud.cholewa.light.error;

import cloud.cholewa.commons.error.model.ErrorId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

//The causes of a command that was not carried out. Their names are the codes of the answer
//(ErrorId.codeOf) - wire contract for a caller that branches on them, the dashboard first of all,
//pinned by LightErrorTest
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum LightError implements ErrorId {

    NOT_FOUND_LIGHT("Light not found"),
    //a brightness for a relay, a color for a light that has none
    UNSUPPORTED_CAPABILITY("Light cannot take this command"),
    DEVICE_FAILED("Light did not take the command"),
    //the lights are not known yet: database-service could not be read since the start
    REGISTRY_UNAVAILABLE("Device registry unavailable");

    private final String description;
}
