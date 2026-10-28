package io.github.litheidea.maven;

import java.util.ArrayList;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingResult;

/**
 * Observes Maven's original settings build before CLI options populate the
 * execution request. Captures exact source revisions rather than re-running
 * a settings builder or deriving a second configuration precedence.
 */
@Named("lithe-native-settings")
@Singleton
public final class NativeSettingsEvents extends AbstractEventSpy {
    private SettingsExport.Capture capture;
    private Exception failure;

    @Override
    public void onEvent(Object event) {
        try {
            if (event instanceof SettingsBuildingRequest) {
                SettingsBuildingRequest request = (SettingsBuildingRequest) event;
                if (!requested(request)) return;
                capture = new SettingsExport.Capture(request);
            } else if (event instanceof SettingsBuildingResult && capture != null) {
                capture.effectiveProfiles = new ArrayList<>(
                        ((SettingsBuildingResult) event).getEffectiveSettings().getActiveProfiles());
            }
        } catch (Exception error) { failure = error; }
    }

    SettingsExport.Capture capture() throws Exception {
        if (failure != null) throw failure;
        return capture;
    }

    private static boolean requested(SettingsBuildingRequest request) {
        for (String key : new String[] {"lithe.configurationOutput", "lithe.configurationContextOutput"}) {
            if (request.getUserProperties().containsKey(key) || request.getSystemProperties().containsKey(key)) return true;
        }
        return false;
    }
}
