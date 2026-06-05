# Other enhancements

- The Personality propmt should be set depending on the personality type, but the user can add more information to it.
- Need a command stack list in the shell, with arrows, etc.
- Use langchain dev.langchain4j.model.catalog to get the list of models and their capabilities, and use that to
  determine which model to use for a given task (instead of using ollama's html)
  **Note:** `ModelCatalog` is `@Experimental` in LangChain4j 1.15.x and Ollama does not implement it — only
  OpenAI-style hosted providers do. The current `GET /api/tags` approach is the correct Ollama-native equivalent.
  For richer per-model metadata (context window, embedding dimensions, etc.), consider calling `POST /api/show`
  for each model. For task-based routing, a simpler alternative is an admin-configurable mapping in server settings
  (e.g. `embedding_model`, `coding_model`, `chat_model`).
