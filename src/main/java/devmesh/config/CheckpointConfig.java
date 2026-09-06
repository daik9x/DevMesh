package devmesh.config;

public class CheckpointConfig {
    private boolean enabled = true;
    private String directory;
    private int maxCheckpoints = 50;
    private int retentionDays = 7;
    private String autoResume = "ask";
    private boolean redactSecrets = true;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDirectory() { return directory; }
    public void setDirectory(String directory) { this.directory = directory; }
    public int getMaxCheckpoints() { return maxCheckpoints; }
    public void setMaxCheckpoints(int maxCheckpoints) { this.maxCheckpoints = maxCheckpoints; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    public String getAutoResume() { return autoResume; }
    public void setAutoResume(String autoResume) { this.autoResume = autoResume; }
    public boolean isRedactSecrets() { return redactSecrets; }
    public void setRedactSecrets(boolean redactSecrets) { this.redactSecrets = redactSecrets; }
}