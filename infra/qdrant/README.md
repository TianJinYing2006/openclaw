# Fashion Wardrobe Qdrant

Qdrant stores rebuildable vectors for confirmed wardrobe items. MySQL remains the source of truth, and image bytes stay in OSS.

## Start

From `D:\YKD-summer`:

```powershell
docker compose -f infra/qdrant/compose.yml up -d
```

Health and dashboard:

- Health: `http://127.0.0.1:6333/healthz`
- Dashboard: `http://127.0.0.1:6333/dashboard`
- Java gRPC: `127.0.0.1:6334`

Both ports bind to localhost only. The container uses the named volume `ykd_qdrant_data`, so vectors survive Java, Docker, and Windows restarts.

## Stop And Restart

```powershell
docker compose -f infra/qdrant/compose.yml stop
docker compose -f infra/qdrant/compose.yml start
```

Do not use `down -v` unless the vector index should be erased and rebuilt. Confirmed wardrobe data is not lost when Qdrant is erased; migration V14 and later wardrobe writes create durable MySQL indexing jobs.

## Application Configuration

The local application uses:

```properties
app.fashion.semantic.enabled=true
app.fashion.semantic.embedding-api-key=${app.tts.api-key}
app.fashion.semantic.embedding-model=text-embedding-v4
app.fashion.semantic.embedding-dimensions=1024
app.fashion.semantic.qdrant-host=127.0.0.1
app.fashion.semantic.qdrant-port=6334
```

`text-embedding-v4` was selected for short Chinese fashion-attribute retrieval. `qwen3-rerank` is a later reranking option, not an embedding replacement.

## Runtime Flow

```text
confirmed wardrobe item -> MySQL transaction + durable index job
  -> background Fashion worker -> Bailian text-embedding-v4
  -> Qdrant vector + appUserId/wardrobeItemId metadata
  -> search_wardrobe_semantic -> user-isolated vector search
  -> active item facts reloaded from MySQL
```

If Qdrant or Bailian is temporarily unavailable, the job retries without blocking the WeChat reply. Semantic search falls back to existing MySQL structured filters.
