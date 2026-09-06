package devmesh.config;

import java.util.List;

public class AppConfig {

    private List<ProviderConfig> providers;
    private String permissionMode;

    private List<McpServerConfig> mcpServers;
    private List<HookConfig> hooks;


    private SandboxYamlConfig sandbox;
    private CheckpointConfig checkpoint;
    private PersistenceConfig persistence;

    private boolean enableCoordinatorMode;

    public List<ProviderConfig> getProviders() { return providers; }

    public void setProviders(List<ProviderConfig> providers) { this.providers = providers; }

    public String getPermissionMode() { return permissionMode; }

    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }

    public List<McpServerConfig> getMcpServers() { return mcpServers; }
    public void setMcpServers(List<McpServerConfig> mcpServers) { this.mcpServers = mcpServers; }

    public List<HookConfig> getHooks() { return hooks; }
    public void setHooks(List<HookConfig> hooks) { this.hooks = hooks; }

    public SandboxYamlConfig getSandbox() { return sandbox; }
    public void setSandbox(SandboxYamlConfig sandbox) { this.sandbox = sandbox; }

    public boolean isSandboxEnabled() { return sandbox != null && sandbox.isEnabled(); }
    public boolean isSandboxAutoAllow() { return sandbox == null || sandbox.isAutoAllow(); }
    public boolean isSandboxNetworkEnabled() { return sandbox != null && sandbox.isNetworkEnabled(); }

    public CheckpointConfig getCheckpoint() { return checkpoint; }
    public void setCheckpoint(CheckpointConfig checkpoint) { this.checkpoint = checkpoint; }
    public PersistenceConfig getPersistence() { return persistence; }
    public void setPersistence(PersistenceConfig persistence) { this.persistence = persistence; }

    public boolean isEnableCoordinatorMode() { return enableCoordinatorMode; }
    public void setEnableCoordinatorMode(boolean enableCoordinatorMode) { this.enableCoordinatorMode = enableCoordinatorMode; }
}
