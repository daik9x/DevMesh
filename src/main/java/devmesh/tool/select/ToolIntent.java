package devmesh.tool.select;

public enum ToolIntent {
    READ_FILE,
    SEARCH_CODE,
    SEARCH_SYMBOL,
    ANALYZE_REPOSITORY,
    EDIT_FILE,
    CREATE_FILE,
    DELETE_FILE,
    CREATE_DIRECTORY,
    COPY_FILE,
    MOVE_FILE,
    FILE_INFO,
    HASH_FILE,
    RUN_BUILD,
    RUN_TEST,
    RUN_COMMAND,
    GIT_STATUS,
    GIT_DIFF,
    INSPECT_ERROR,
    UNKNOWN
}