package com.thehiddenbrain.interop.extract.runtime;

import com.thehiddenbrain.interop.extract.config.AppProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Switches an admin can flip at run time from the Settings page. */
@Service
public class DemoSettings {

    public volatile boolean axwaySimulator;
    public volatile int axwayPickupSeconds;
    public volatile boolean allowSelfApproval = false;
    public volatile boolean schedulerEnabled = true;
    public volatile int hoursPerHandBuiltExtract;
    public volatile int sampleRetentionDays = 30;

    public DemoSettings(AppProperties props) {
        axwaySimulator = props.isAxwaySimulator();
        axwayPickupSeconds = props.getAxwayPickupSeconds();
        hoursPerHandBuiltExtract = props.getHoursPerHandBuiltExtract();
    }

    public Map<String, Object> asMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("axwaySimulator", axwaySimulator);
        m.put("axwayPickupSeconds", axwayPickupSeconds);
        m.put("allowSelfApproval", allowSelfApproval);
        m.put("schedulerEnabled", schedulerEnabled);
        m.put("hoursPerHandBuiltExtract", hoursPerHandBuiltExtract);
        m.put("sampleRetentionDays", sampleRetentionDays);
        return m;
    }

    public void apply(Map<String, Object> m) {
        if (m.containsKey("axwaySimulator")) axwaySimulator = Boolean.parseBoolean(String.valueOf(m.get("axwaySimulator")));
        if (m.containsKey("axwayPickupSeconds")) axwayPickupSeconds = Math.max(1, Integer.parseInt(String.valueOf(m.get("axwayPickupSeconds"))));
        if (m.containsKey("allowSelfApproval")) allowSelfApproval = Boolean.parseBoolean(String.valueOf(m.get("allowSelfApproval")));
        if (m.containsKey("schedulerEnabled")) schedulerEnabled = Boolean.parseBoolean(String.valueOf(m.get("schedulerEnabled")));
        if (m.containsKey("hoursPerHandBuiltExtract")) hoursPerHandBuiltExtract = Math.max(1, Integer.parseInt(String.valueOf(m.get("hoursPerHandBuiltExtract"))));
        if (m.containsKey("sampleRetentionDays")) sampleRetentionDays = Math.max(1, Integer.parseInt(String.valueOf(m.get("sampleRetentionDays"))));
    }
}
