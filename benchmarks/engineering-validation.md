# DevMesh Engineering Validation Benchmark

This report is generated offline by `./gradlew engineeringValidation` using real Tool Schemas, real `ToolSearch` registration state, and the real `ContextCompactor.manage`; it does not call an external model.

## 1. On-demand Tool Schema discovery

Schema tokens are an **estimate**: tools are sorted by name and serialized as canonical compact JSON, then `ceil(JSON character count / 4)` is calculated. This is the same implementation used by the Agent trace and is not claimed to match any model's private tokenizer.

The baseline discovers all 21 built-in tools in interactive mode and injects all schemas every turn. The fixed discovery input is `ToolSearch(select:AskUserQuestion,EnterWorktree,ExitWorktree,ProposeSkillCandidate,TaskCreate,TaskUpdate)`.

| Protocol | Scenario | Schema count | JSON characters | Estimated tokens | Reduction vs. full |
| --- | --- | ---: | ---: | ---: | ---: |
| anthropic | Full baseline | 27 | 18434 | 4609 | — |
| anthropic | Cold-start resident | 13 | 11902 | 2976 | 35.43% |
| anthropic | After discovering six deferred tools | 19 | 16755 | 4189 | 9.11% |
| openai-compat | Full baseline | 27 | 18866 | 4717 | — |
| openai-compat | Cold-start resident | 13 | 12110 | 3028 | 35.81% |
| openai-compat | After discovering six deferred tools | 19 | 17059 | 4265 | 9.58% |

## 2. 50-turn context compaction

The input is fixed by `benchmarks/fixtures/context-50-turns.yaml`: 50 turns, each with a 700-character user message and a 1,200-character assistant message; every five turns add one `ReadFile` tool_use, a 5,000-character tool_result, and a 600-character follow-up, for 120 messages and 10 tool-call groups. The window is 32768 tokens with 4096 tokens reserved for output.

The baseline disables `ContextCompactor.manage` for the same input; the treatment calls the real `manage` before each model request. To remove external API variability, the summary client returns fixed text. This benchmark validates triggering, reconstruction, and tool-pair integrity, not summary quality. The metric is `estimated message tokens + estimated fixed resident Schema tokens` after compaction management on each turn.

| Metric | No-compaction baseline | Automatic compaction | Reduction |
| --- | ---: | ---: | ---: |
| 50-turn average estimated context | 24966.88 | 11111.96 | 55.49% |
| 50-turn peak estimated context | 47367 | 18529 | 60.88% |
| Final value at turn 50 | 47367 | 5711 | — |

Automatic compaction triggered 3 times; tool_use/tool_result pairing was checked on all 50 turns with 0 failures.

See `benchmarks/results/engineering-validation.json` for the raw per-turn sequence and input SHA-256. These figures apply only to the fixed input and estimator above and should not be generalized to all models, tasks, or context windows.
