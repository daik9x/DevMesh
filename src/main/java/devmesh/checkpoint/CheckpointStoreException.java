package devmesh.checkpoint;
public class CheckpointStoreException extends RuntimeException {
    public CheckpointStoreException(String message, Throwable cause) { super(message, cause); }
    public CheckpointStoreException(String message) { super(message); }
}