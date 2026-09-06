package devmesh.config;

public class PersistenceConfig {
    private String databasePath = ".devmesh/devmesh.db";
    private boolean wal = true;
    private int busyTimeoutMs = 5000;
    private boolean ftsEnabled = true;
    public String getDatabasePath() { return databasePath; }
    public void setDatabasePath(String databasePath) { this.databasePath = databasePath; }
    public boolean isWal() { return wal; }
    public void setWal(boolean wal) { this.wal = wal; }
    public int getBusyTimeoutMs() { return busyTimeoutMs; }
    public void setBusyTimeoutMs(int busyTimeoutMs) { this.busyTimeoutMs = busyTimeoutMs; }
    public boolean isFtsEnabled() { return ftsEnabled; }
    public void setFtsEnabled(boolean ftsEnabled) { this.ftsEnabled = ftsEnabled; }
}