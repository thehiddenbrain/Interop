package com.thehiddenbrain.interop.extract.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Where the application keeps its files. Everything lives under {@code dataDir}: the developer-maintained
 * catalog and source data, the seed state used on first start, and the mutable state written at run time.
 */
@ConfigurationProperties(prefix = "extract")
public class AppProperties {

    /** Root data directory, relative to the working directory by default. */
    private Path dataDir = Path.of("data");
    /** Version stamp recorded on every run so a frozen definition can be traced to the engine that ran it. */
    private String engineVersion = "1.0.0";
    /** Seconds the simulated Axway poller waits before it picks a file up from a drop folder. */
    private int axwayPickupSeconds = 8;
    /** Whether the simulated Axway poller runs at all. */
    private boolean axwaySimulator = true;
    /** Estimated developer hours a hand-built extract costs; used by the dashboard's savings tile. */
    private int hoursPerHandBuiltExtract = 40;

    public Path getDataDir() { return dataDir; }
    public void setDataDir(Path dataDir) { this.dataDir = dataDir; }
    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }
    public int getAxwayPickupSeconds() { return axwayPickupSeconds; }
    public void setAxwayPickupSeconds(int axwayPickupSeconds) { this.axwayPickupSeconds = axwayPickupSeconds; }
    public boolean isAxwaySimulator() { return axwaySimulator; }
    public void setAxwaySimulator(boolean axwaySimulator) { this.axwaySimulator = axwaySimulator; }
    public int getHoursPerHandBuiltExtract() { return hoursPerHandBuiltExtract; }
    public void setHoursPerHandBuiltExtract(int hoursPerHandBuiltExtract) { this.hoursPerHandBuiltExtract = hoursPerHandBuiltExtract; }

    public Path catalogFile() { return dataDir.resolve("catalog.json"); }
    public Path sourceDir() { return dataDir.resolve("source"); }
    public Path seedDir() { return dataDir.resolve("seed"); }
    public Path stateDir() { return dataDir.resolve("state"); }
    public Path samplesDir() { return dataDir.resolve("samples"); }
    public Path stagingDir() { return dataDir.resolve("staging"); }
    public Path mftDir() { return dataDir.resolve("mft"); }
}
