package devmesh.checkpoint;
public enum RecoveryDecision { RESUME, VERIFY_THEN_RESUME, RESTART_CURRENT_STEP, ASK_USER, ABORT }